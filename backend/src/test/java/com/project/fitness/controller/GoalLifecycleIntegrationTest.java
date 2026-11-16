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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — a goal brought back from expiry counts what it should.
 *
 * <p>Found by the full-app test run of 2026-11-16: an expired weekly goal is frozen, so
 * extending its deadline brought it back at whatever it held when it ended. Workouts
 * already logged this week were never counted until the next Monday's roll-over.
 *
 * <p>Per AGENT.md §7, plain java.net.http.HttpClient — not TestRestTemplate.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Goal lifecycle")
class GoalLifecycleIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newHttpClient();
    private String jwt, userId;

    @BeforeEach
    void freshUser() throws Exception {
        String email = "goal-" + UUID.randomUUID() + "@test.com";
        userId = json(send(request("POST", "/api/auth/register", Map.of("email", email, "password", "testPass123",
                "firstName", "Go", "lastName", "Al", "weight", 70.0, "height", 175.0, "age", 30), null))).get("id").asText();
        jwt = json(send(request("POST", "/api/auth/login", Map.of("email", email, "password", "testPass123"), null)))
                .get("accessToken").asText();
    }

    @Test
    @DisplayName("A goal created already expired and then extended counts this week's workouts")
    void extendedExpiredGoalCountsThisWeek() throws Exception {
        logWorkout(30, 300);
        logWorkout(20, 200);
        String yesterday = LocalDate.now().minusDays(1).toString();
        String workouts = createGoal("WORKOUTS_PER_WEEK", 5, yesterday);
        String minutes = createGoal("DURATION_PER_WEEK", 300, yesterday);
        String kcal = createGoal("CALORIES_PER_WEEK", 3000, yesterday);
        assertThat(goal(workouts).get("isExpired").asBoolean()).isTrue();
        assertThat(goal(workouts).get("currentValue").asDouble()).isZero();

        String later = LocalDate.now().plusDays(30).toString();
        for (String id : new String[] {workouts, minutes, kcal}) {
            assertThat(send(request("PUT", "/api/goals/" + id, Map.of("deadline", later), jwt)).statusCode()).isEqualTo(200);
        }
        assertThat(goal(workouts).get("isExpired").asBoolean()).isFalse();
        assertThat(goal(workouts).get("currentValue").asDouble()).isEqualTo(2.0);
        assertThat(goal(minutes).get("currentValue").asDouble()).isEqualTo(50.0);
        assertThat(goal(kcal).get("currentValue").asDouble()).isEqualTo(500.0);

        logWorkout(10, 100);   // and it keeps counting from there
        assertThat(goal(workouts).get("currentValue").asDouble()).isEqualTo(3.0);
        assertThat(goal(kcal).get("currentValue").asDouble()).isEqualTo(600.0);
    }

    @Test
    @DisplayName("A goal that lapsed and was extended again counts the workout logged while it was expired")
    void lapsedAndRevivedGoalCatchesUp() throws Exception {
        String id = createGoal("WORKOUTS_PER_WEEK", 5, LocalDate.now().plusDays(30).toString());
        logWorkout(30, 300);
        assertThat(goal(id).get("currentValue").asDouble()).isEqualTo(1.0);

        send(request("PUT", "/api/goals/" + id, Map.of("deadline", LocalDate.now().minusDays(1).toString()), jwt));
        logWorkout(30, 300);   // not counted: the goal is expired
        assertThat(goal(id).get("currentValue").asDouble()).isEqualTo(1.0);

        send(request("PUT", "/api/goals/" + id, Map.of("deadline", LocalDate.now().plusDays(30).toString()), jwt));
        assertThat(goal(id).get("currentValue").asDouble()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("Editing an active goal's target leaves its progress alone")
    void activeGoalEditKeepsProgress() throws Exception {
        String id = createGoal("WORKOUTS_PER_WEEK", 5, null);
        logWorkout(30, 300);
        send(request("PUT", "/api/goals/" + id, Map.of("targetValue", 4), jwt));
        assertThat(goal(id).get("currentValue").asDouble()).isEqualTo(1.0);
        assertThat(goal(id).get("targetValue").asDouble()).isEqualTo(4.0);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void logWorkout(int minutes, int kcal) throws Exception {
        HttpResponse<String> r = send(request("POST", "/api/activities", Map.of("type", "RUNNING", "duration", minutes,
                "caloriesBurned", kcal, "startTime", LocalDateTime.now().withNano(0).toString()), jwt));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    }

    private String createGoal(String type, double target, String deadline) throws Exception {
        Map<String, Object> body = deadline == null ? Map.of("type", type, "targetValue", target)
                : Map.of("type", type, "targetValue", target, "deadline", deadline);
        HttpResponse<String> r = send(request("POST", "/api/goals", body, jwt));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json(r).get("id").asText();
    }

    private JsonNode goal(String id) throws Exception {
        for (JsonNode g : json(send(request("GET", "/api/goals/" + userId, null, jwt)))) {
            if (g.get("id").asText().equals(id)) return g;
        }
        throw new AssertionError("goal " + id + " not in the list");
    }

    private String url(String path) { return "http://localhost:" + port + path; }

    private JsonNode json(HttpResponse<String> r) throws Exception { return objectMapper.readTree(r.body()); }

    private HttpResponse<String> send(HttpRequest r) throws Exception {
        return http.send(r, HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(String method, String path, Object body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(url(path)));
        if (body != null) {
            b.header("Content-Type", "application/json")
             .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        if (token != null) b.header("Authorization", "Bearer " + token);
        return b.build();
    }
}
