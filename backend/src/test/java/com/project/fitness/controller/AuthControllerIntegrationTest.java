package com.project.fitness.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.fitness.dto.LoginRequest;
import com.project.fitness.dto.RegisterRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — Auth flow (register + login + refresh + logout)
 * Each test is fully self-contained — no shared state between tests.
 * Uses Java 21 built-in HttpClient, no spring-boot-test-* HTTP client needed.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Auth Controller Integration Tests")
class AuthControllerIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpRequest post(String path, Object body) throws Exception {
        return HttpRequest.newBuilder()
                .uri(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body)))
                .build();
    }

    // ── Register ──────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("POST /api/auth/register — valid request: 200, userId returned, no password")
    void register_validRequest_returns200() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setEmail("auth-test-1@test.com");
        req.setPassword("securePass123");
        req.setFirstName("Test");
        req.setLastName("User");
        req.setWeight(75.0);
        req.setHeight(175.0);
        req.setAge(25);

        HttpResponse<String> resp = http.send(
                post("/api/auth/register", req),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(200);
        var body = objectMapper.readTree(resp.body());
        assertThat(body.get("id").asText()).isNotBlank();
        assertThat(body.get("email").asText()).isEqualTo("auth-test-1@test.com");
        assertThat(body.has("password")).isFalse();  // Fix 3: password never in response
        assertThat(body.get("weight").asDouble()).isEqualTo(75.0);
    }

    @Test
    @Order(2)
    @DisplayName("POST /api/auth/register — duplicate email: 400 Bad Request")
    void register_duplicateEmail_returns400() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setEmail("dup-test@test.com");
        req.setPassword("pass123");
        req.setFirstName("Dup");
        req.setLastName("User");

        // First registration succeeds
        http.send(post("/api/auth/register", req), HttpResponse.BodyHandlers.ofString());

        // Second registration with same email fails
        HttpResponse<String> resp = http.send(
                post("/api/auth/register", req),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(400);
    }

    @Test
    @Order(3)
    @DisplayName("POST /api/auth/register — blank email: 400 validation error")
    void register_blankEmail_returns400() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setEmail("");
        req.setPassword("pass123");

        HttpResponse<String> resp = http.send(
                post("/api/auth/register", req),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(400);
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("POST /api/auth/login — correct credentials: accessToken + refreshToken")
    void login_correctCredentials_returnsBothTokens() throws Exception {
        // Register unique user for this test
        RegisterRequest reg = new RegisterRequest();
        reg.setEmail("login-test-1@test.com");
        reg.setPassword("securePass123");
        reg.setFirstName("Login");
        reg.setLastName("User");
        http.send(post("/api/auth/register", reg), HttpResponse.BodyHandlers.ofString());

        // Login
        LoginRequest login = new LoginRequest();
        login.setEmail("login-test-1@test.com");
        login.setPassword("securePass123");

        HttpResponse<String> resp = http.send(
                post("/api/auth/login", login),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(200);
        var body = objectMapper.readTree(resp.body());

        // accessToken: 3-part JWT
        String accessToken = body.get("accessToken").asText();
        assertThat(accessToken).isNotBlank();
        assertThat(accessToken.split("\\.")).hasSize(3);

        // Phase 4: refreshToken also present
        String refreshToken = body.get("refreshToken").asText();
        assertThat(refreshToken).isNotBlank();
        assertThat(refreshToken).isNotEqualTo(accessToken);
    }

    @Test
    @Order(5)
    @DisplayName("POST /api/auth/login — wrong password: 401 Unauthorized")
    void login_wrongPassword_returns401() throws Exception {
        // Register
        RegisterRequest reg = new RegisterRequest();
        reg.setEmail("wrongpass-test@test.com");
        reg.setPassword("correctPass123");
        reg.setFirstName("Test"); reg.setLastName("User");
        http.send(post("/api/auth/register", reg), HttpResponse.BodyHandlers.ofString());

        // Login with wrong password
        LoginRequest login = new LoginRequest();
        login.setEmail("wrongpass-test@test.com");
        login.setPassword("WRONG_PASSWORD");

        HttpResponse<String> resp = http.send(
                post("/api/auth/login", login),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(401);
    }

    @Test
    @Order(6)
    @DisplayName("POST /api/auth/login — unknown email: 401 Unauthorized")
    void login_unknownEmail_returns401() throws Exception {
        LoginRequest login = new LoginRequest();
        login.setEmail("nobody-exists@test.com");
        login.setPassword("anyPass123");

        HttpResponse<String> resp = http.send(
                post("/api/auth/login", login),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(401);
    }

    // ── Refresh token ─────────────────────────────────────────────────────────

    @Test
    @Order(7)
    @DisplayName("POST /api/auth/refresh — valid refresh token: new accessToken returned")
    void refresh_validToken_returnsNewAccessToken() throws Exception {
        // Register + login to get refresh token
        RegisterRequest reg = new RegisterRequest();
        reg.setEmail("refresh-test@test.com");
        reg.setPassword("securePass123");
        reg.setFirstName("Refresh"); reg.setLastName("User");
        http.send(post("/api/auth/register", reg), HttpResponse.BodyHandlers.ofString());

        LoginRequest login = new LoginRequest();
        login.setEmail("refresh-test@test.com");
        login.setPassword("securePass123");
        HttpResponse<String> loginResp = http.send(
                post("/api/auth/login", login),
                HttpResponse.BodyHandlers.ofString());

        String refreshToken = objectMapper.readTree(loginResp.body())
                .get("refreshToken").asText();

        // Use refresh token
        HttpResponse<String> refreshResp = http.send(
                post("/api/auth/refresh",
                        java.util.Map.of("refreshToken", refreshToken)),
                HttpResponse.BodyHandlers.ofString());

        assertThat(refreshResp.statusCode()).isEqualTo(200);
        var body = objectMapper.readTree(refreshResp.body());
        assertThat(body.get("accessToken").asText()).isNotBlank();
        assertThat(body.get("refreshToken").asText()).isNotBlank();
    }

    @Test
    @Order(8)
    @DisplayName("POST /api/auth/refresh — fake token: 401 Unauthorized")
    void refresh_fakeToken_returns401() throws Exception {
        HttpResponse<String> resp = http.send(
                post("/api/auth/refresh",
                        java.util.Map.of("refreshToken", "fake-uuid-does-not-exist")),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(401);
    }
}
