package com.project.fitness.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.fitness.dto.ActivityRequest;
import com.project.fitness.dto.LoginRequest;
import com.project.fitness.dto.RegisterRequest;
import com.project.fitness.model.ActivityType;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — a session that outlived its account.
 *
 * The real-world trigger is a database reset (or a switch to a fresh database)
 * while a browser still has a token and user in localStorage. The token is still
 * perfectly valid — right signature, not expired — so the request authenticates
 * fine and only fails when the user id inside it is looked up.
 *
 * This used to surface as a 400 "User not found: <id>", which the frontend has no
 * way to distinguish from an ordinary validation error: it left the person on a
 * working-looking page where every panel failed with a message they couldn't
 * clear. It now returns a 409 carrying the phrase the frontend matches to clear
 * the session and send them to the login screen (see app.js's api() wrapper and
 * AGENT.md §8).
 *
 * Per AGENT.md §7, integration tests here use plain java.net.http.HttpClient —
 * NOT TestRestTemplate or @AutoConfigureMockMvc.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// Ordered: the last test deletes the user row the earlier two rely on.
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Dead Session Recovery Integration Tests")
class DeadSessionRecoveryIntegrationTest {

    /** The substring app.js matches on. If this test fails, session self-healing is broken. */
    private static final String FRONTEND_MATCHED_PHRASE = "account that no longer exists";

    @LocalServerPort private int port;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    private String jwt;
    private String userId;

    @BeforeAll
    void registerAndLogin() throws Exception {
        RegisterRequest reg = new RegisterRequest();
        reg.setEmail("deadsession@test.com");
        reg.setPassword("testPass123");
        reg.setFirstName("Dead");
        reg.setLastName("Session");
        reg.setWeight(75.0);
        reg.setHeight(175.0);
        reg.setAge(25);

        HttpResponse<String> regResp = send(post("/api/auth/register", reg, null));
        assertThat(regResp.statusCode()).isEqualTo(200);
        userId = objectMapper.readTree(regResp.body()).get("id").asText();

        LoginRequest login = new LoginRequest();
        login.setEmail("deadsession@test.com");
        login.setPassword("testPass123");

        HttpResponse<String> loginResp = send(post("/api/auth/login", login, null));
        assertThat(loginResp.statusCode()).isEqualTo(200);
        jwt = objectMapper.readTree(loginResp.body()).get("accessToken").asText();
    }

    @Test
    @Order(1)
    @DisplayName("A healthy session is untouched — no false positive")
    void healthySession_stillWorks() throws Exception {
        assertThat(send(get("/api/profile/" + userId)).statusCode()).isEqualTo(200);
        assertThat(send(post("/api/activities", runningActivity(), jwt)).statusCode()).isEqualTo(200);
    }

    @Test
    @Order(2)
    @DisplayName("Another user missing is still an ordinary 400 — it must NOT log the caller out")
    void unknownOpponent_isNotADeadSession() throws Exception {
        HttpResponse<String> resp = send(post("/api/challenges", Map.of(
                "opponentId", "no-such-user-id",
                "type", "WORKOUT_COUNT",
                "durationDays", 7), jwt));

        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(resp.body()).doesNotContain(FRONTEND_MATCHED_PHRASE);
    }

    @Test
    @Order(3)
    @DisplayName("Account gone, token still valid: every endpoint returns 409 with the phrase app.js matches")
    void deadSession_returns409WithRecoverablePhrase() throws Exception {
        // Simulate the database being reset out from under a saved session. The FK
        // suspension is what a DROP/CREATE does wholesale; here it lets the user row
        // go while this test's own rows stay put.
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        jdbc.update("DELETE FROM fitness_user WHERE id = ?", userId);
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");

        // The frontend's boot check — this is what decides whether a returning
        // visitor lands in the app or back on the login screen.
        assertRecoverable(send(get("/api/profile/" + userId)));

        // ...and a write from a tab that was already open when the database went away.
        assertRecoverable(send(post("/api/activities", runningActivity(), jwt)));

        // The streamed exports (PLAN P4-1). These write straight to the response, so a
        // lookup that ran after the headers went out used to commit an empty 200.
        assertRecoverable(send(get("/api/export/json")));
        assertRecoverable(send(get("/api/export/csv/activities")));
        assertRecoverable(send(get("/api/export/zip")));
    }

    private void assertRecoverable(HttpResponse<String> resp) throws Exception {
        assertThat(resp.statusCode()).isEqualTo(409);
        String message = objectMapper.readTree(resp.body()).get("error").asText();
        assertThat(message.toLowerCase()).contains(FRONTEND_MATCHED_PHRASE);
        // The raw id is a detail of our storage — it belongs in the log, not the response.
        assertThat(message).doesNotContain(userId);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ActivityRequest runningActivity() {
        ActivityRequest act = new ActivityRequest();
        act.setType(ActivityType.RUNNING);
        act.setDuration(30);
        act.setStartTime(LocalDateTime.now());
        return act;
    }

    private String url(String path) { return "http://localhost:" + port + path; }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder().uri(URI.create(url(path)))
                .header("Authorization", "Bearer " + jwt).GET().build();
    }

    private HttpRequest post(String path, Object body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return b.build();
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
