package com.project.fitness.controller;

import com.project.fitness.config.ProductionSettingsCheck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The app as deployed, with the prod profile on (PLAN P5-14 to P5-16).
 *
 * <p>Layered on the test profile for its H2 database. The settings the startup check
 * requires are supplied here as they would be on Render. FRONTEND_URL carries a path, as
 * a GitHub Pages project site's does.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "app.frontend-url=https://name.github.io/fitpilot",
        "app.mail.api-key=xkeysib-test",
        "app.mail.from=FitPilot <sender@example.com>"})
@ActiveProfiles({"test", "prod"})
@DisplayName("Production profile")
class ProductionProfileIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private ApplicationContext context;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("the startup check ran, and passed")
    void startupCheckIsActive() {
        assertThat(context.getBeansOfType(ProductionSettingsCheck.class)).hasSize(1);
    }

    @Test
    @DisplayName("the deployed frontend's origin may call the API")
    void frontendOriginAllowed() throws Exception {
        HttpResponse<String> res = preflight("https://name.github.io");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.headers().firstValue("Access-Control-Allow-Origin")).hasValue("https://name.github.io");
    }

    @Test
    @DisplayName("localhost, 127.0.0.1 and file:// may not")
    void devOriginsRefused() throws Exception {
        for (String origin : new String[]{"http://localhost:5500", "http://127.0.0.1:5500", "null"}) {
            HttpResponse<String> res = preflight(origin);
            assertThat(res.statusCode()).as(origin).isEqualTo(403);
            assertThat(res.headers().firstValue("Access-Control-Allow-Origin")).as(origin).isEmpty();
        }
    }

    /**
     * Read from the file rather than the running Environment, because the test profile
     * underneath already turns these off and would hide a prod file that didn't.
     */
    @Test
    @DisplayName("application-prod.properties turns off SQL logging and Swagger")
    void prodFileTurnsOffSqlAndSwagger() throws Exception {
        Properties prod = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application-prod.properties"));
        assertThat(prod.getProperty("spring.jpa.show-sql")).isEqualTo("false");
        assertThat(prod.getProperty("springdoc.api-docs.enabled")).isEqualTo("false");
        assertThat(prod.getProperty("springdoc.swagger-ui.enabled")).isEqualTo("false");
        assertThat(prod.getProperty("app.cors.allow-local-origins")).isEqualTo("false");
    }

    @Test
    @DisplayName("the API docs are not served")
    void apiDocsNotServed() throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(uri("/v3/api-docs")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> preflight(String origin) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(uri("/api/activities"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization")
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
