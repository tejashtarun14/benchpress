package com.palominolabs.benchpress;

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Stage;
import com.ning.http.client.AsyncHttpClient;
import com.palominolabs.benchpress.config.ZookeeperConfig;
import com.palominolabs.benchpress.controller.JobFarmer;
import com.palominolabs.benchpress.controller.ResourceModule;
import com.palominolabs.benchpress.controller.zookeeper.ZKServer;
import com.palominolabs.benchpress.controller.zookeeper.ZKServerModule;
import com.palominolabs.benchpress.curator.InstanceSerializerModule;
import com.palominolabs.benchpress.http.server.DefaultJerseyServletModule;
import com.palominolabs.benchpress.ipc.Ipc;
import com.palominolabs.benchpress.ipc.IpcHttpClientModule;
import com.palominolabs.benchpress.ipc.IpcJsonModule;
import com.palominolabs.benchpress.job.registry.JobRegistryModule;
import com.palominolabs.benchpress.job.task.TaskFactoryFactoryRegistryModule;
import com.palominolabs.benchpress.job.task.TaskPartitionerRegistryModule;
import com.palominolabs.benchpress.task.reporting.TaskProgressClientModule;
import com.palominolabs.benchpress.worker.WorkerAdvertiser;
import com.palominolabs.benchpress.worker.WorkerControl;
import com.palominolabs.benchpress.worker.WorkerControlFactory;
import com.palominolabs.benchpress.worker.WorkerMetadata;
import com.palominolabs.benchpress.zookeeper.CuratorModule;
import com.palominolabs.config.ConfigModuleBuilder;
import com.palominolabs.http.server.HttpServer;
import com.palominolabs.http.server.HttpServerConfig;
import com.palominolabs.http.server.HttpServerFactory;
import org.apache.commons.configuration.MapConfiguration;
import org.apache.curator.x.discovery.ServiceDiscovery;
import org.apache.curator.x.discovery.ServiceInstance;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.bridge.SLF4JBridgeHandler;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.LogManager;

import static com.google.inject.Guice.createInjector;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SingleVmIntegrationTest {

    @Inject
    WorkerAdvertiser workerAdvertiser;
    @Inject
    HttpServerFactory httpServerFactory;
    @Inject
    ZKServer zkServer;
    @Inject
    CuratorModule.CuratorLifecycleHook curatorLifecycleHook;
    @Inject
    @Ipc
    AsyncHttpClient asyncHttpClient;
    @Inject
    ServiceDiscovery<WorkerMetadata> serviceDiscovery;
    @Inject
    ZookeeperConfig zookeeperConfig;
    @Inject
    WorkerControlFactory workerControlFactory;
    @Inject
    JobFarmer jobFarmer;

    ExecutorService executorService;
    HttpServer httpServer;
    private HttpServerConfig httpServerConfig;

    @BeforeClass
    public static void setUpClass() {
        LogManager.getLogManager().reset();
        SLF4JBridgeHandler.install();
    }

    @Before
    public void setUp() throws Exception {

        final Map<String, Object> configMap = new HashMap<String, Object>();

        createInjector(Stage.PRODUCTION, new AbstractModule() {
            @Override
            protected void configure() {
                install(new ConfigModuleBuilder().addConfiguration(new MapConfiguration(configMap))
                    .build());

                // basic zookeeper
                install(new ZKServerModule());
                install(new CuratorModule());

                install(new DefaultJerseyServletModule());

                // controller
                install(new InstanceSerializerModule());
                install(new IpcJsonModule());
                install(new TaskPartitionerRegistryModule());
                install(new ResourceModule());

                // worker
                install(new JobRegistryModule());
                install(new TaskProgressClientModule());
                install(new IpcHttpClientModule());
                install(new TaskFactoryFactoryRegistryModule());
                install(new com.palominolabs.benchpress.worker.http.ResourceModule());
            }
        }).injectMembers(this);

        executorService = Executors.newCachedThreadPool();
        executorService.submit(zkServer);

        httpServerConfig = new HttpServerConfig();
        httpServer = httpServerFactory.getHttpServer(this.httpServerConfig);
        httpServer.start();

        curatorLifecycleHook.start();
    }

    @After
    public void tearDown() throws Exception {
        httpServer.stop();

        executorService.shutdownNow();

        serviceDiscovery.close();
    }

    @Test
    public void testLockDeAdvertises() throws Exception {
        WorkerMetadata workerMetadata = advertiseWorker();

        WorkerControl workerControl = workerControlFactory.getWorkerControl(workerMetadata);
        assertTrue(workerControl.acquireLock(jobFarmer.getControllerId()));

        assertNoWorkersAdvertised();
    }

    private void assertNoWorkersAdvertised() throws Exception {
        Collection<ServiceInstance<WorkerMetadata>> instances =
            serviceDiscovery.queryForInstances(zookeeperConfig.getWorkerServiceName());
        assertEquals(0, instances.size());
    }

    /**
     * @return the metadata loaded from ZK
     * @throws Exception
     */
    private WorkerMetadata advertiseWorker() throws Exception {
        workerAdvertiser.initListenInfo(httpServerConfig.getHttpListenHost(), httpServerConfig.getHttpListenPort());
        workerAdvertiser.advertiseAvailability();

        Collection<ServiceInstance<WorkerMetadata>> instances =
            serviceDiscovery.queryForInstances(zookeeperConfig.getWorkerServiceName());
        assertEquals(1, instances.size());

        WorkerMetadata workerMetadata = instances.iterator().next().getPayload();

        assertEquals(workerAdvertiser.getWorkerId(), workerMetadata.getWorkerId());

        return workerMetadata;
    }
}
