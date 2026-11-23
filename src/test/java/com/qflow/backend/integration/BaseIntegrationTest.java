package com.qflow.backend.integration;

import de.flapdoodle.embed.mongo.config.Net;
import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.mongo.transitions.Mongod;
import de.flapdoodle.embed.mongo.transitions.RunningMongodProcess;
import de.flapdoodle.reverse.TransitionWalker;
import de.flapdoodle.reverse.transitions.Start;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
public abstract class BaseIntegrationTest {

    private static TransitionWalker.ReachedState<RunningMongodProcess> mongod;
    // Random free port so the embedded server never clashes with a locally installed MongoDB on 27017
    private static final int MONGO_PORT = freePort();

    private static int freePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeAll
    static void startMongo() {
        mongod = Mongod.builder()
                .net(Start.to(Net.class).initializedWith(Net.defaults().withPort(MONGO_PORT)))
                .build()
                .start(Version.Main.V6_0);
    }

    @AfterAll
    static void stopMongo() {
        if (mongod != null) {
            mongod.close();
        }
    }

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        // Use the embedded MongoDB (no auth, localhost)
        registry.add("spring.mongodb.uri", () -> "mongodb://localhost:" + MONGO_PORT + "/qflow");
        registry.add("jwt.secret", () -> "integration-test-secret-key-that-is-at-least-32-bytes-long");
        registry.add("jwt.expiration", () -> "3600000");

        // Disable SSL for tests – use plain HTTP
        registry.add("server.ssl.enabled", () -> "false");
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> "6379");
        registry.add("spring.cache.type", () -> "none");
    }



}