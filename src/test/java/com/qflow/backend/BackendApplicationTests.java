package com.qflow.backend;

import com.qflow.backend.config.TestFirebaseConfig;
import com.qflow.backend.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

@Import(TestFirebaseConfig.class)
class BackendApplicationTests extends BaseIntegrationTest {

    @Test
    void contextLoads() {
    }
}