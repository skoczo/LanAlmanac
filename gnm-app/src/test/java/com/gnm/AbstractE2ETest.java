package com.gnm;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.File;
import java.time.Duration;

@QuarkusTest
public abstract class AbstractE2ETest {

    private static final String COMPOSE_FILE_PATH = "../docker-compose.e2e.yml";

    public static ComposeContainer environment =
            new ComposeContainer(new File(COMPOSE_FILE_PATH))
                    .withPull(false)
                    .withExposedService("ne-linux-server", 22, Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(5)));

    static {
        environment.start();
    }
}
