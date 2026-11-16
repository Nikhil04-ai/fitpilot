package com.project.fitness.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — password and email edge cases found by the full-app test run of
 * 2026-11-16: one rule everywhere for a blank password, nothing past BCrypt's 72 bytes
 * matching at login, and an email trimmed before it is validated.
 *
 * <p>Per AGENT.md §7, plain java.net.http.HttpClient — not TestRestTemplate.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Auth edge cases")
class AuthEdgeCasesIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("Registration refuses a blank password, and so do reset and change now")
    void blankPasswordRefusedEverywhere() throws Exception {
        String email = "blank-" + UUID.randomUUID() + "@test.com";
        assertThat(call("POST", "/api/auth/register", reg(email, "        "), null).statusCode()).isEqualTo(400);

        assertThat(call("POST", "/api/auth/register", reg(email, "testPass123"), null).statusCode()).isEqualTo(200);
        String token = login(email, "testPass123");
        String userId = json(call("POST", "/api/auth/login", Map.of("email", email, "password", "testPass123"), null))
                .get("user").get("id").asText();

        for (String blank : new String[] {"        ", "\t\t\t\t\t\t\t\t"}) {
            HttpResponse<String> change = call("PUT", "/api/profile/" + userId + "/password",
                    Map.of("currentPassword", "testPass123", "newPassword", blank), token);
            assertThat(change.statusCode()).as(change.body()).isEqualTo(400);
            assertThat(change.body()).contains("only spaces");

            // Checked before the token, like the length rules, so no real link is needed.
            HttpResponse<String> reset = call("POST", "/api/auth/reset-password",
                    Map.of("token", "not-a-real-token", "newPassword", blank), null);
            assertThat(reset.statusCode()).as(reset.body()).isEqualTo(400);
            assertThat(reset.body()).contains("only spaces");
        }
        assertThat(login(email, "testPass123")).isNotBlank();   // nothing changed
    }

    @Test
    @DisplayName("Login doesn't accept a 72-byte password with anything after it")
    void nothingPastSeventyTwoBytesMatches() throws Exception {
        String email = "long-" + UUID.randomUUID() + "@test.com";
        String password = "b".repeat(72);
        assertThat(call("POST", "/api/auth/register", reg(email, password), null).statusCode()).isEqualTo(200);

        assertThat(call("POST", "/api/auth/login", Map.of("email", email, "password", password + "anything-else"), null)
                .statusCode()).isEqualTo(401);
        assertThat(call("POST", "/api/auth/login", Map.of("email", email, "password", password), null)
                .statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("An email with spaces around it registers, as API.md says, and logs in without them")
    void paddedEmailRegisters() throws Exception {
        String email = "pad-" + UUID.randomUUID() + "@test.com";
        HttpResponse<String> r = call("POST", "/api/auth/register", reg("  " + email + " ", "testPass123"), null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(json(r).get("email").asText()).isEqualTo(email);
        assertThat(login(email, "testPass123")).isNotBlank();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> reg(String email, String password) {
        return Map.of("email", email, "password", password, "firstName", "Ed", "lastName", "Ge",
                "weight", 70.0, "height", 175.0, "age", 30);
    }

    private String login(String email, String password) throws Exception {
        HttpResponse<String> r = call("POST", "/api/auth/login", Map.of("email", email, "password", password), null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json(r).get("accessToken").asText();
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        return r.body() == null || r.body().isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(r.body());
    }

    private HttpResponse<String> call(String method, String path, Object body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path));
        if (body != null) {
            b.header("Content-Type", "application/json")
             .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
