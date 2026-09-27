package com.gnm;

import io.quarkus.test.junit.QuarkusTest;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.File;
import java.time.Duration;

@QuarkusTest
@org.junit.jupiter.api.Tag("e2e")
abstract class AbstractE2ETest {

    private static final String COMPOSE_FILE_PATH = "../docker-compose.e2e.yml";

    public static ComposeContainer environment =
            new ComposeContainer(new File(COMPOSE_FILE_PATH))
                    .withPull(false)
                    .withExposedService("ne-linux-server", 22, Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(5)))
                    .withExposedService("ne-router-sim", 22, Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(5)));

    static {
        environment.start();
    }
}
