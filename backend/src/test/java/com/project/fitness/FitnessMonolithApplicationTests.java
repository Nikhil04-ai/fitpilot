package com.project.fitness;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies the whole Spring context wires up: every controller, service, repository,
 * filter and @Bean, against H2.
 *
 * WebEnvironment.MOCK on purpose — this needs to check bean wiring, not serve HTTP,
 * and starting a real Tomcat here bought nothing (the two *IntegrationTest classes
 * already exercise the real HTTP surface on a random port) while making this test
 * unrunnable on machines where the embedded server can't bind.
 */
@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
@ActiveProfiles("test")   // H2, not MySQL
class FitnessMonolithApplicationTests {

    @Test
    void contextLoads() {
        // Fails if any bean can't be created — e.g. a filter whose dependency is missing.
    }
}
