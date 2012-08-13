package com.palominolabs.benchpress.controller;

import com.google.inject.AbstractModule;
import com.palominolabs.benchpress.curator.InstanceSerializerModule;
import com.palominolabs.benchpress.ipc.IpcJsonModule;
import com.palominolabs.benchpress.zookeeper.CuratorModule;
import com.palominolabs.config.ConfigModule;
import com.palominolabs.config.ConfigModuleBuilder;
import com.palominolabs.http.server.HttpServerModule;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.MetricsRegistry;
import org.apache.commons.configuration.SystemConfiguration;

public final class ControllerMainModule extends AbstractModule {
    @Override
    protected void configure() {
        binder().requireExplicitBindings();
        bind(ControllerMain.class);

        install(new HttpServerModule());

        bind(MetricsRegistry.class).toInstance(Metrics.defaultRegistry());

        install(new ControllerServletModule());

        install(new ResourceModule());
        install(new ConfigModuleBuilder().addConfiguration(new SystemConfiguration()).build());

        install(new CuratorModule());

        install(new InstanceSerializerModule());

        install(new IpcJsonModule());

        ConfigModule.bindConfigBean(binder(), ControllerConfig.class);
    }
}
