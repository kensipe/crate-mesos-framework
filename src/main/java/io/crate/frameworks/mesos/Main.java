package io.crate.frameworks.mesos;

import com.google.common.base.Optional;
import com.google.protobuf.ByteString;
import io.crate.frameworks.mesos.api.CrateHttpService;
import io.crate.frameworks.mesos.config.ApiConfiguration;
import io.crate.frameworks.mesos.config.ClusterConfiguration;
import io.crate.frameworks.mesos.config.ResourceConfiguration;
import org.apache.log4j.BasicConfigurator;
import org.apache.mesos.MesosSchedulerDriver;
import org.apache.mesos.Protos;
import org.apache.mesos.Scheduler;
import org.apache.mesos.state.ZooKeeperState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;


public class Main {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    private static final String ZK_URL = "localhost:2181";

    public static void main(String[] args) throws Exception {
        BasicConfigurator.configure();

        final int frameworkFailoverTimeout = 60 * 60;

        Protos.FrameworkInfo.Builder frameworkBuilder = Protos.FrameworkInfo.newBuilder()
                .setName("CrateFramework")
                .setUser("root")
                .setFailoverTimeout(frameworkFailoverTimeout); // timeout in seconds

        ClusterConfiguration clusterConfiguration = ClusterConfiguration.fromEnvironment();
        PersistentStateStore stateStore = new PersistentStateStore(
                new ZooKeeperState(ZK_URL, 20_000, TimeUnit.MILLISECONDS,
                        String.format("/crate-mesos/%s", clusterConfiguration.clusterName())),
                clusterConfiguration);

        Optional<String> frameworkId = stateStore.state().frameworkId();
        if (frameworkId.isPresent()) {
            frameworkBuilder.setId(Protos.FrameworkID.newBuilder().setValue(frameworkId.get()).build());
        }

        if (System.getenv("MESOS_CHECKPOINT") != null) {
            System.out.println("Enabling checkpoint for the framework");
            frameworkBuilder.setCheckpoint(true);
        }

        ResourceConfiguration resourceConfiguration = ResourceConfiguration.fromEnvironment();
        final Scheduler scheduler = new CrateScheduler(stateStore, resourceConfiguration, clusterConfiguration);

        // create the driver
        MesosSchedulerDriver driver;

        String mesosMaster = Env.option("MESOS_MASTER").or("127.0.0.1:5050");

        if (System.getenv("MESOS_AUTHENTICATE") != null) {
            System.out.println("Enabling authentication for the framework");

            if (System.getenv("DEFAULT_PRINCIPAL") == null) {
                System.err.println("Expecting authentication principal in the environment");
                System.exit(1);
            }

            if (System.getenv("DEFAULT_SECRET") == null) {
                System.err.println("Expecting authentication secret in the environment");
                System.exit(1);
            }

            Protos.Credential credential = Protos.Credential.newBuilder()
                    .setPrincipal(System.getenv("DEFAULT_PRINCIPAL"))
                    .setSecret(ByteString.copyFrom(System.getenv("DEFAULT_SECRET").getBytes()))
                    .build();

            frameworkBuilder.setPrincipal(System.getenv("DEFAULT_PRINCIPAL"));
            driver = new MesosSchedulerDriver(scheduler, frameworkBuilder.build(), mesosMaster, credential);
        } else {
            frameworkBuilder.setPrincipal("crate-framework");
            driver = new MesosSchedulerDriver(scheduler, frameworkBuilder.build(), mesosMaster);
        }

        CrateHttpService api = new CrateHttpService(stateStore, ApiConfiguration.fromEnvironment());
        api.start();

        int status = driver.run() == Protos.Status.DRIVER_STOPPED ? 0 : 1;

        // Ensure that the driver process terminates.
        api.stop();
        driver.stop();
        System.exit(status);
    }

}
