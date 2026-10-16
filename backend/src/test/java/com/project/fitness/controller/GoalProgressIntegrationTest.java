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
 * Integration test — an activity goal's progress counts the same workouts however it
 * is reached.
 *
 * <p>Found by the live QA pass. A weekly goal started at 0 even when that week already
 * had workouts; each save then added to the running total whatever the workout's date;
 * and the rebuild after an edit or delete counted the week's workouts by date. So
 * deleting a workout made a goal go up (2 → 3), and a run backdated five weeks counted
 * towards this week.
 *
 * <p>Workouts are dated early today, so "this week" holds on any day the suite runs,
 * Monday included. Per AGENT.md §7, plain java.net.http.HttpClient.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Goal Progress Integration Tests")
class GoalProgressIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("A goal created mid-week counts that week's earlier workouts")
    void newGoalCountsTheWeekSoFar() throws Exception {
        Session s = newUser();
        logRun(s, LocalDate.now().atTime(0, 8), 30);
        logRun(s, LocalDate.now().atTime(0, 9), 20);

        String goalId = createGoal(s, "WORKOUTS_PER_WEEK", 5);
        assertThat(progress(s, goalId)).isEqualTo(2.0);
    }

    @Test
    @DisplayName("Deleting a workout lowers a goal, never raises it")
    void deletingNeverRaisesAGoal() throws Exception {
        Session s = newUser();
        logRun(s, LocalDate.now().atTime(0, 7), 30);
        String goalId = createGoal(s, "WORKOUTS_PER_WEEK", 5);
        logRun(s, LocalDate.now().atTime(0, 8), 30);
        String third = logRun(s, LocalDate.now().atTime(0, 9), 30);
        assertThat(progress(s, goalId)).isEqualTo(3.0);

        assertThat(send(s, "DELETE", "/api/activities/" + third, null).statusCode()).isEqualTo(204);
        assertThat(progress(s, goalId)).isEqualTo(2.0);
    }

    @Test
    @DisplayName("A workout backdated to an earlier week does not count towards this week")
    void backdatedWorkoutDoesNotCount() throws Exception {
        Session s = newUser();
        String goalId = createGoal(s, "DURATION_PER_WEEK", 300);
        logRun(s, LocalDateTime.now().minusWeeks(5), 60);
        assertThat(progress(s, goalId)).isEqualTo(0.0);

        logRun(s, LocalDate.now().atTime(0, 9), 45);
        assertThat(progress(s, goalId)).isEqualTo(45.0);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private record Session(String userId, String token) {}

    private Session newUser() throws Exception {
        String email = "goals-" + UUID.randomUUID() + "@test.com";
        send(null, "POST", "/api/auth/register",
                Map.of("email", email, "password", "testPass123", "firstName", "Go", "weight", 70.0));
        JsonNode login = json(send(null, "POST", "/api/auth/login", Map.of("email", email, "password", "testPass123")));
        return new Session(login.get("user").get("id").asText(), login.get("accessToken").asText());
    }

    private String logRun(Session s, LocalDateTime at, int minutes) throws Exception {
        HttpResponse<String> r = send(s, "POST", "/api/activities",
                Map.of("type", "RUNNING", "duration", minutes, "startTime", at.withNano(0).toString()));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json(r).get("id").asText();
    }

    private String createGoal(Session s, String type, double target) throws Exception {
        HttpResponse<String> r = send(s, "POST", "/api/goals", Map.of("type", type, "targetValue", target));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json(r).get("id").asText();
    }

    private double progress(Session s, String goalId) throws Exception {
        for (JsonNode g : json(send(s, "GET", "/api/goals/" + s.userId(), null))) {
            if (g.get("id").asText().equals(goalId)) return g.get("currentValue").asDouble();
        }
        throw new AssertionError("goal not listed: " + goalId);
    }

    private HttpResponse<String> send(Session s, String method, String path, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        if (s != null) b.header("Authorization", "Bearer " + s.token());
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception { return objectMapper.readTree(r.body()); }
}
