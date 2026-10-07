package com.project.fitness.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — inputs that were the client's mistake but came back as a 500
 * (PLAN P4-7). A 500 says "our fault, retry later", hides what to correct, and gets
 * logged as a server fault. Each of these is now a 400 that names the problem.
 *
 * <p>Per AGENT.md §7, plain java.net.http.HttpClient — not TestRestTemplate.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Bad Input Integration Tests")
class BadInputIntegrationTest {

    /** 73 ASCII characters: one byte past what BCrypt can hash. */
    private static final String TOO_LONG = "a".repeat(73);
    /** 24 characters but 96 bytes in UTF-8, so a character count alone misses it. */
    private static final String TOO_LONG_IN_BYTES = "💪".repeat(24);

    @LocalServerPort private int port;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    private String jwt, userId;

    @BeforeAll
    void setUp() throws Exception {
        HttpResponse<String> reg = send(post("/api/auth/register", Map.of(
                "email", "badinput@test.com", "password", "testPass123",
                "firstName", "Bad", "lastName", "Input", "weight", 70.0, "height", 175.0, "age", 30), null));
        assertThat(reg.statusCode()).isEqualTo(200);
        userId = json(reg).get("id").asText();
        jwt = json(send(post("/api/auth/login",
                Map.of("email", "badinput@test.com", "password", "testPass123"), null))).get("accessToken").asText();
    }

    // ── Passwords BCrypt can't hash ─────────────────────────────────────────

    @Test
    @DisplayName("Registering with a password over 72 bytes is a 400")
    void registerWithOverlongPassword() throws Exception {
        HttpResponse<String> r = send(post("/api/auth/register", Map.of(
                "email", "longpw@test.com", "password", TOO_LONG, "firstName", "Long", "lastName", "Pw"), null));
        assertBadRequest(r, "72");
    }

    @Test
    @DisplayName("72 bytes is measured in bytes, not characters")
    void registerWithMultibyteOverlongPassword() throws Exception {
        HttpResponse<String> r = send(post("/api/auth/register", Map.of(
                "email", "emojipw@test.com", "password", TOO_LONG_IN_BYTES, "firstName", "Emoji", "lastName", "Pw"), null));
        assertBadRequest(r, "72");
    }

    @Test
    @DisplayName("Changing to a password over 72 bytes is a 400, and the old password still works")
    void changeToOverlongPassword() throws Exception {
        HttpResponse<String> r = send(put("/api/profile/" + userId + "/password",
                Map.of("currentPassword", "testPass123", "newPassword", TOO_LONG)));
        assertBadRequest(r, "72");
        assertThat(send(post("/api/auth/login",
                Map.of("email", "badinput@test.com", "password", "testPass123"), null)).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("Resetting to a password over 72 bytes is a 400")
    void resetToOverlongPassword() throws Exception {
        HttpResponse<String> r = send(post("/api/auth/reset-password",
                Map.of("token", "any-token", "newPassword", TOO_LONG), null));
        assertBadRequest(r, "72");
    }

    // ── Missing required fields ─────────────────────────────────────────────

    @Test
    @DisplayName("Logging an activity with no type is a 400")
    void activityWithoutType() throws Exception {
        HttpResponse<String> r = send(post("/api/activities",
                Map.of("duration", 30, "startTime", LocalDateTime.now().toString()), jwt));
        assertBadRequest(r, "type");
    }

    @Test
    @DisplayName("Generating a recommendation with no activityId is a 400")
    void recommendationWithoutActivity() throws Exception {
        HttpResponse<String> r = send(post("/api/recommendation/generate", Map.of(), jwt));
        assertBadRequest(r, "activityId");
    }

    @Test
    @DisplayName("Logging pain with no body part is a 400")
    void painWithoutBodyPart() throws Exception {
        HttpResponse<String> r = send(post("/api/pain-log", Map.of("severity", 3), jwt));
        assertBadRequest(r, "body part");
    }

    @Test
    @DisplayName("Pain patterns survive an old entry with no body part")
    void patternsSurviveANullBodyPart() throws Exception {
        // Rows like this could be written before the check above existed.
        jdbc.update("INSERT INTO pain_log (id, user_id, body_part, severity, logged_at) VALUES (?, ?, NULL, 2, ?)",
                UUID.randomUUID().toString(), userId, LocalDateTime.now());

        HttpResponse<String> r = send(get("/api/pain-log/patterns/" + userId));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void assertBadRequest(HttpResponse<String> r, String mentions) throws Exception {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(400);
        assertThat(json(r).get("error").asText().toLowerCase()).contains(mentions.toLowerCase());
    }

    private String url(String path) { return "http://localhost:" + port + path; }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return objectMapper.readTree(response.body());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest post(String path, Object body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return b.build();
    }

    private HttpRequest put(String path, Object body) throws Exception {
        return HttpRequest.newBuilder().uri(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + jwt)
                .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))).build();
    }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder().uri(URI.create(url(path)))
                .header("Authorization", "Bearer " + jwt).GET().build();
    }
}
