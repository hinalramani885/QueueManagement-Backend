package com.queueless.backend;

import com.queueless.backend.config.TestFirebaseConfig;
import com.queueless.backend.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

@Import(TestFirebaseConfig.class)
class BackendApplicationTests extends BaseIntegrationTest {

    @Test
    void contextLoads() {
    }
}