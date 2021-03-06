/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.twitter.distributedlog.impl;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.twitter.distributedlog.BookKeeperClient;
import com.twitter.distributedlog.BookKeeperClientBuilder;
import com.twitter.distributedlog.DistributedLogConfiguration;
import com.twitter.distributedlog.DistributedLogConstants;
import com.twitter.distributedlog.MetadataAccessor;
import com.twitter.distributedlog.ZooKeeperClient;
import com.twitter.distributedlog.ZooKeeperClientBuilder;
import com.twitter.distributedlog.acl.AccessControlManager;
import com.twitter.distributedlog.acl.DefaultAccessControlManager;
import com.twitter.distributedlog.impl.acl.ZKAccessControlManager;
import com.twitter.distributedlog.bk.LedgerAllocator;
import com.twitter.distributedlog.bk.LedgerAllocatorUtils;
import com.twitter.distributedlog.config.DynamicDistributedLogConfiguration;
import com.twitter.distributedlog.exceptions.AlreadyClosedException;
import com.twitter.distributedlog.exceptions.InvalidStreamNameException;
import com.twitter.distributedlog.impl.federated.FederatedZKLogMetadataStore;
import com.twitter.distributedlog.impl.logsegment.BKLogSegmentEntryStore;
import com.twitter.distributedlog.impl.metadata.ZKLogStreamMetadataStore;
import com.twitter.distributedlog.impl.subscription.ZKSubscriptionsStore;
import com.twitter.distributedlog.injector.AsyncFailureInjector;
import com.twitter.distributedlog.logsegment.LogSegmentEntryStore;
import com.twitter.distributedlog.impl.metadata.BKDLConfig;
import com.twitter.distributedlog.metadata.LogMetadataForReader;
import com.twitter.distributedlog.metadata.LogMetadataStore;
import com.twitter.distributedlog.metadata.LogStreamMetadataStore;
import com.twitter.distributedlog.namespace.NamespaceDriver;
import com.twitter.distributedlog.namespace.NamespaceDriverManager;
import com.twitter.distributedlog.subscription.SubscriptionsStore;
import com.twitter.distributedlog.util.OrderedScheduler;
import com.twitter.distributedlog.util.Utils;
import org.apache.bookkeeper.feature.FeatureProvider;
import org.apache.bookkeeper.stats.StatsLogger;
import org.apache.bookkeeper.zookeeper.BoundExponentialBackoffRetryPolicy;
import org.apache.bookkeeper.zookeeper.RetryPolicy;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.common.PathUtils;
import org.apache.zookeeper.data.Stat;
import org.jboss.netty.channel.socket.ClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.util.HashedWheelTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.twitter.distributedlog.util.DLUtils.isReservedStreamName;
import static com.twitter.distributedlog.util.DLUtils.validateName;

/**
 * Manager for ZooKeeper/BookKeeper based namespace
 */
public class BKNamespaceDriver implements NamespaceDriver {

    private static Logger LOG = LoggerFactory.getLogger(BKNamespaceDriver.class);

    // register itself
    static {
        NamespaceDriverManager.registerDriver(DistributedLogConstants.BACKEND_BK, BKNamespaceDriver.class);
    }

    /**
     * Extract zk servers fro dl <i>namespace</i>.
     *
     * @param uri dl namespace
     * @return zk servers
     */
    public static String getZKServersFromDLUri(URI uri) {
        return uri.getAuthority().replace(";", ",");
    }

    // resources (passed from initialization)
    private DistributedLogConfiguration conf;
    private DynamicDistributedLogConfiguration dynConf;
    private URI namespace;
    private OrderedScheduler scheduler;
    private FeatureProvider featureProvider;
    private AsyncFailureInjector failureInjector;
    private StatsLogger statsLogger;
    private StatsLogger perLogStatsLogger;
    private String clientId;
    private int regionId;

    //
    // resources (created internally and initialized at #initialize())
    //

    // namespace binding
    private BKDLConfig bkdlConfig;

    // zookeeper clients
    // NOTE: The actual zookeeper client is initialized lazily when it is referenced by
    //       {@link com.twitter.distributedlog.ZooKeeperClient#get()}. So it is safe to
    //       keep builders and their client wrappers here, as they will be used when
    //       instantiating readers or writers.
    private ZooKeeperClientBuilder sharedWriterZKCBuilder;
    private ZooKeeperClient writerZKC;
    private ZooKeeperClientBuilder sharedReaderZKCBuilder;
    private ZooKeeperClient readerZKC;
    // NOTE: The actual bookkeeper client is initialized lazily when it is referenced by
    //       {@link com.twitter.distributedlog.BookKeeperClient#get()}. So it is safe to
    //       keep builders and their client wrappers here, as they will be used when
    //       instantiating readers or writers.
    private ClientSocketChannelFactory channelFactory;
    private HashedWheelTimer requestTimer;
    private BookKeeperClientBuilder sharedWriterBKCBuilder;
    private BookKeeperClient writerBKC;
    private BookKeeperClientBuilder sharedReaderBKCBuilder;
    private BookKeeperClient readerBKC;

    // log stream metadata store
    private LogMetadataStore metadataStore;
    private LogStreamMetadataStore writerStreamMetadataStore;
    private LogStreamMetadataStore readerStreamMetadataStore;

    //
    // resources (lazily initialized)
    //

    // ledger allocator
    private LedgerAllocator allocator;

    // log segment entry stores
    private LogSegmentEntryStore writerEntryStore;
    private LogSegmentEntryStore readerEntryStore;

    // access control manager
    private AccessControlManager accessControlManager;

    //
    // states
    //
    protected boolean initialized = false;
    protected AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Public constructor for reflection.
     */
    public BKNamespaceDriver() {
    }

    @Override
    public synchronized NamespaceDriver initialize(DistributedLogConfiguration conf,
                                                   DynamicDistributedLogConfiguration dynConf,
                                                   URI namespace,
                                                   OrderedScheduler scheduler,
                                                   FeatureProvider featureProvider,
                                                   AsyncFailureInjector failureInjector,
                                                   StatsLogger statsLogger,
                                                   StatsLogger perLogStatsLogger,
                                                   String clientId,
                                                   int regionId) throws IOException {
        if (initialized) {
            return this;
        }
        // validate the namespace
        if ((null == namespace) || (null == namespace.getAuthority()) || (null == namespace.getPath())) {
            throw new IOException("Incorrect distributedlog namespace : " + namespace);
        }

        // initialize the resources
        this.conf = conf;
        this.dynConf = dynConf;
        this.namespace = namespace;
        this.scheduler = scheduler;
        this.featureProvider = featureProvider;
        this.failureInjector = failureInjector;
        this.statsLogger = statsLogger;
        this.perLogStatsLogger = perLogStatsLogger;
        this.clientId = clientId;
        this.regionId = regionId;

        // initialize the zookeeper clients
        initializeZooKeeperClients();

        // initialize the bookkeeper clients
        initializeBookKeeperClients();

        // propagate bkdlConfig to configuration
        BKDLConfig.propagateConfiguration(bkdlConfig, conf);

        // initialize the log metadata & stream metadata store
        initializeLogStreamMetadataStores();

        // initialize other resources
        initializeOtherResources();

        initialized = true;

        LOG.info("Initialized BK namespace driver: clientId = {}, regionId = {}, federated = {}.",
                new Object[]{clientId, regionId, bkdlConfig.isFederatedNamespace()});
        return this;
    }

    private void initializeZooKeeperClients() throws IOException {
        // Build the namespace zookeeper client
        this.sharedWriterZKCBuilder = createZKClientBuilder(
                String.format("dlzk:%s:factory_writer_shared", namespace),
                conf,
                getZKServersFromDLUri(namespace),
                statsLogger.scope("dlzk_factory_writer_shared"));
        this.writerZKC = sharedWriterZKCBuilder.build();

        // Resolve namespace binding
        this.bkdlConfig = BKDLConfig.resolveDLConfig(writerZKC, namespace);

        // Build zookeeper client for readers
        if (bkdlConfig.getDlZkServersForWriter().equals(bkdlConfig.getDlZkServersForReader())) {
            this.sharedReaderZKCBuilder = this.sharedWriterZKCBuilder;
        } else {
            this.sharedReaderZKCBuilder = createZKClientBuilder(
                    String.format("dlzk:%s:factory_reader_shared", namespace),
                    conf,
                    bkdlConfig.getDlZkServersForReader(),
                    statsLogger.scope("dlzk_factory_reader_shared"));
        }
        this.readerZKC = this.sharedReaderZKCBuilder.build();
    }

    private synchronized BKDLConfig getBkdlConfig() {
        return bkdlConfig;
    }

    private void initializeBookKeeperClients() throws IOException {
        this.channelFactory = new NioClientSocketChannelFactory(
                Executors.newCachedThreadPool(new ThreadFactoryBuilder().setNameFormat("DL-netty-boss-%d").build()),
                Executors.newCachedThreadPool(new ThreadFactoryBuilder().setNameFormat("DL-netty-worker-%d").build()),
                conf.getBKClientNumberIOThreads());
        this.requestTimer = new HashedWheelTimer(
                new ThreadFactoryBuilder().setNameFormat("DLFactoryTimer-%d").build(),
                conf.getTimeoutTimerTickDurationMs(), TimeUnit.MILLISECONDS,
                conf.getTimeoutTimerNumTicks());
        // Build bookkeeper client for writers
        this.sharedWriterBKCBuilder = createBKCBuilder(
                String.format("bk:%s:factory_writer_shared", namespace),
                conf,
                bkdlConfig.getBkZkServersForWriter(),
                bkdlConfig.getBkLedgersPath(),
                channelFactory,
                requestTimer,
                Optional.of(featureProvider.scope("bkc")),
                statsLogger);
        this.writerBKC = this.sharedWriterBKCBuilder.build();

        // Build bookkeeper client for readers
        if (bkdlConfig.getBkZkServersForWriter().equals(bkdlConfig.getBkZkServersForReader())) {
            this.sharedReaderBKCBuilder = this.sharedWriterBKCBuilder;
        } else {
            this.sharedReaderBKCBuilder = createBKCBuilder(
                    String.format("bk:%s:factory_reader_shared", namespace),
                    conf,
                    bkdlConfig.getBkZkServersForReader(),
                    bkdlConfig.getBkLedgersPath(),
                    channelFactory,
                    requestTimer,
                    Optional.<FeatureProvider>absent(),
                    statsLogger);
        }
        this.readerBKC = this.sharedReaderBKCBuilder.build();
    }

    private void initializeLogStreamMetadataStores() throws IOException {
        // log metadata store
        if (bkdlConfig.isFederatedNamespace() || conf.isFederatedNamespaceEnabled()) {
            this.metadataStore = new FederatedZKLogMetadataStore(conf, namespace, readerZKC, scheduler);
        } else {
            this.metadataStore = new ZKLogMetadataStore(conf, namespace, readerZKC, scheduler);
        }

        // create log stream metadata store
        this.writerStreamMetadataStore =
                new ZKLogStreamMetadataStore(
                        clientId,
                        conf,
                        writerZKC,
                        scheduler,
                        statsLogger);
        this.readerStreamMetadataStore =
                new ZKLogStreamMetadataStore(
                        clientId,
                        conf,
                        readerZKC,
                        scheduler,
                        statsLogger);
    }

    @VisibleForTesting
    public static String validateAndGetFullLedgerAllocatorPoolPath(DistributedLogConfiguration conf, URI uri) throws IOException {
        String poolPath = conf.getLedgerAllocatorPoolPath();
        LOG.info("PoolPath is {}", poolPath);
        if (null == poolPath || !poolPath.startsWith(".") || poolPath.endsWith("/")) {
            LOG.error("Invalid ledger allocator pool path specified when enabling ledger allocator pool : {}", poolPath);
            throw new IOException("Invalid ledger allocator pool path specified : " + poolPath);
        }
        String poolName = conf.getLedgerAllocatorPoolName();
        if (null == poolName) {
            LOG.error("No ledger allocator pool name specified when enabling ledger allocator pool.");
            throw new IOException("No ledger allocator name specified when enabling ledger allocator pool.");
        }
        String rootPath = uri.getPath() + "/" + poolPath + "/" + poolName;
        try {
            PathUtils.validatePath(rootPath);
        } catch (IllegalArgumentException iae) {
            LOG.error("Invalid ledger allocator pool path specified when enabling ledger allocator pool : {}", poolPath);
            throw new IOException("Invalid ledger allocator pool path specified : " + poolPath);
        }
        return rootPath;
    }

    private void initializeOtherResources() throws IOException {
        // Ledger allocator
        if (conf.getEnableLedgerAllocatorPool()) {
            String allocatorPoolPath = validateAndGetFullLedgerAllocatorPoolPath(conf, namespace);
            allocator = LedgerAllocatorUtils.createLedgerAllocatorPool(
                    allocatorPoolPath,
                    conf.getLedgerAllocatorPoolCoreSize(),
                    conf,
                    writerZKC,
                    writerBKC,
                    scheduler);
            if (null != allocator) {
                allocator.start();
            }
            LOG.info("Created ledger allocator pool under {} with size {}.", allocatorPoolPath, conf.getLedgerAllocatorPoolCoreSize());
        } else {
            allocator = null;
        }

    }

    private void checkState() throws IOException {
        if (closed.get()) {
            LOG.error("BK namespace driver {} is already closed", namespace);
            throw new AlreadyClosedException("BK namespace driver " + namespace + " is already closed");
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        doClose();
    }

    private void doClose() {
        if (null != accessControlManager) {
            accessControlManager.close();
            LOG.info("Access Control Manager Stopped.");
        }

        // Close the allocator
        if (null != allocator) {
            Utils.closeQuietly(allocator);
            LOG.info("Ledger Allocator stopped.");
        }

        // Shutdown log segment metadata stores
        Utils.close(writerStreamMetadataStore);
        Utils.close(readerStreamMetadataStore);

        writerBKC.close();
        readerBKC.close();
        writerZKC.close();
        readerZKC.close();
        // release bookkeeper resources
        channelFactory.releaseExternalResources();
        LOG.info("Release external resources used by channel factory.");
        requestTimer.stop();
        LOG.info("Stopped request timer");
    }

    @Override
    public URI getUri() {
        return namespace;
    }

    @Override
    public String getScheme() {
        return DistributedLogConstants.BACKEND_BK;
    }

    @Override
    public LogMetadataStore getLogMetadataStore() {
        return metadataStore;
    }

    @Override
    public LogStreamMetadataStore getLogStreamMetadataStore(Role role) {
        if (Role.WRITER == role) {
            return writerStreamMetadataStore;
        } else {
            return readerStreamMetadataStore;
        }
    }

    @Override
    public LogSegmentEntryStore getLogSegmentEntryStore(Role role) {
        if (Role.WRITER == role) {
            return getWriterEntryStore();
        } else {
            return getReaderEntryStore();
        }
    }

    private LogSegmentEntryStore getWriterEntryStore() {
        if (null == writerEntryStore) {
            writerEntryStore = new BKLogSegmentEntryStore(
                    conf,
                    dynConf,
                    writerZKC,
                    writerBKC,
                    scheduler,
                    allocator,
                    statsLogger,
                    failureInjector);
        }
        return writerEntryStore;
    }

    private LogSegmentEntryStore getReaderEntryStore() {
        if (null == readerEntryStore) {
            readerEntryStore = new BKLogSegmentEntryStore(
                    conf,
                    dynConf,
                    writerZKC,
                    readerBKC,
                    scheduler,
                    allocator,
                    statsLogger,
                    failureInjector);
        }
        return readerEntryStore;
    }

    @Override
    public AccessControlManager getAccessControlManager() throws IOException {
        if (null == accessControlManager) {
            String aclRootPath = getBkdlConfig().getACLRootPath();
            // Build the access control manager
            if (aclRootPath == null) {
                accessControlManager = DefaultAccessControlManager.INSTANCE;
                LOG.info("Created default access control manager for {}", namespace);
            } else {
                if (!isReservedStreamName(aclRootPath)) {
                    throw new IOException("Invalid Access Control List Root Path : " + aclRootPath);
                }
                String zkRootPath = namespace.getPath() + "/" + aclRootPath;
                LOG.info("Creating zk based access control manager @ {} for {}",
                        zkRootPath, namespace);
                accessControlManager = new ZKAccessControlManager(conf, readerZKC,
                        zkRootPath, scheduler);
                LOG.info("Created zk based access control manager @ {} for {}",
                        zkRootPath, namespace);
            }
        }
        return accessControlManager;
    }

    @Override
    public SubscriptionsStore getSubscriptionsStore(String streamName) {
        return new ZKSubscriptionsStore(
                writerZKC,
                LogMetadataForReader.getSubscribersPath(namespace, streamName, conf.getUnpartitionedStreamName()));
    }

    //
    // Legacy Intefaces
    //

    @Override
    public MetadataAccessor getMetadataAccessor(String streamName)
            throws InvalidStreamNameException, IOException {
        if (getBkdlConfig().isFederatedNamespace()) {
            throw new UnsupportedOperationException();
        }
        checkState();
        validateName(streamName);
        return new ZKMetadataAccessor(
                streamName,
                conf,
                namespace,
                sharedWriterZKCBuilder,
                sharedReaderZKCBuilder,
                statsLogger);
    }

    public Map<String, byte[]> enumerateLogsWithMetadataInNamespace()
        throws IOException, IllegalArgumentException {
        String namespaceRootPath = namespace.getPath();
        HashMap<String, byte[]> result = new HashMap<String, byte[]>();
        ZooKeeperClient zkc = writerZKC;
        try {
            ZooKeeper zk = Utils.sync(zkc, namespaceRootPath);
            Stat currentStat = zk.exists(namespaceRootPath, false);
            if (currentStat == null) {
                return result;
            }
            List<String> children = zk.getChildren(namespaceRootPath, false);
            for(String child: children) {
                if (isReservedStreamName(child)) {
                    continue;
                }
                String zkPath = String.format("%s/%s", namespaceRootPath, child);
                currentStat = zk.exists(zkPath, false);
                if (currentStat == null) {
                    result.put(child, new byte[0]);
                } else {
                    result.put(child, zk.getData(zkPath, false, currentStat));
                }
            }
        } catch (InterruptedException ie) {
            LOG.error("Interrupted while deleting " + namespaceRootPath, ie);
            throw new IOException("Interrupted while reading " + namespaceRootPath, ie);
        } catch (KeeperException ke) {
            LOG.error("Error reading" + namespaceRootPath + "entry in zookeeper", ke);
            throw new IOException("Error reading" + namespaceRootPath + "entry in zookeeper", ke);
        }
        return result;
    }

    //
    // Zk & Bk Utils
    //

    public static ZooKeeperClientBuilder createZKClientBuilder(String zkcName,
                                                               DistributedLogConfiguration conf,
                                                               String zkServers,
                                                               StatsLogger statsLogger) {
        RetryPolicy retryPolicy = null;
        if (conf.getZKNumRetries() > 0) {
            retryPolicy = new BoundExponentialBackoffRetryPolicy(
                conf.getZKRetryBackoffStartMillis(),
                conf.getZKRetryBackoffMaxMillis(), conf.getZKNumRetries());
        }
        ZooKeeperClientBuilder builder = ZooKeeperClientBuilder.newBuilder()
            .name(zkcName)
            .sessionTimeoutMs(conf.getZKSessionTimeoutMilliseconds())
            .retryThreadCount(conf.getZKClientNumberRetryThreads())
            .requestRateLimit(conf.getZKRequestRateLimit())
            .zkServers(zkServers)
            .retryPolicy(retryPolicy)
            .statsLogger(statsLogger)
            .zkAclId(conf.getZkAclId());
        LOG.info("Created shared zooKeeper client builder {}: zkServers = {}, numRetries = {}, sessionTimeout = {}, retryBackoff = {},"
                + " maxRetryBackoff = {}, zkAclId = {}.", new Object[] { zkcName, zkServers, conf.getZKNumRetries(),
                conf.getZKSessionTimeoutMilliseconds(), conf.getZKRetryBackoffStartMillis(),
                conf.getZKRetryBackoffMaxMillis(), conf.getZkAclId() });
        return builder;
    }

    private BookKeeperClientBuilder createBKCBuilder(String bkcName,
                                                     DistributedLogConfiguration conf,
                                                     String zkServers,
                                                     String ledgersPath,
                                                     ClientSocketChannelFactory channelFactory,
                                                     HashedWheelTimer requestTimer,
                                                     Optional<FeatureProvider> featureProviderOptional,
                                                     StatsLogger statsLogger) {
        BookKeeperClientBuilder builder = BookKeeperClientBuilder.newBuilder()
                .name(bkcName)
                .dlConfig(conf)
                .zkServers(zkServers)
                .ledgersPath(ledgersPath)
                .channelFactory(channelFactory)
                .requestTimer(requestTimer)
                .featureProvider(featureProviderOptional)
                .statsLogger(statsLogger);
        LOG.info("Created shared client builder {} : zkServers = {}, ledgersPath = {}, numIOThreads = {}",
                new Object[] { bkcName, zkServers, ledgersPath, conf.getBKClientNumberIOThreads() });
        return builder;
    }

    //
    // Test Methods
    //

    @VisibleForTesting
    public ZooKeeperClient getWriterZKC() {
        return writerZKC;
    }

    @VisibleForTesting
    public BookKeeperClient getReaderBKC() {
        return readerBKC;
    }

    @VisibleForTesting
    public AsyncFailureInjector getFailureInjector() {
        return this.failureInjector;
    }

    @VisibleForTesting
    public LogStreamMetadataStore getWriterStreamMetadataStore() {
        return writerStreamMetadataStore;
    }

    @VisibleForTesting
    public LedgerAllocator getLedgerAllocator() {
        return allocator;
    }
}
