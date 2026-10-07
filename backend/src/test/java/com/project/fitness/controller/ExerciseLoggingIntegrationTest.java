package com.project.fitness.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.fitness.dto.ActivityRequest;
import com.project.fitness.dto.ExerciseRequest;
import com.project.fitness.dto.LoginRequest;
import com.project.fitness.dto.RegisterRequest;
import com.project.fitness.model.ActivityType;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercise-level logging end to end (PLAN P1-1).
 *
 * <p>Plain {@code java.net.http.HttpClient}, not TestRestTemplate or MockMvc — both
 * moved artifact in Spring Boot 4.1 and broke this build twice (AGENT.md rule 8).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Exercise Logging Integration Tests")
class ExerciseLoggingIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();

    private String jwt;
    private String userId;
    private String liftingActivityId;

    @BeforeAll
    void setupOnce() throws Exception {
        RegisterRequest reg = new RegisterRequest();
        reg.setEmail("exercisetest@test.com");
        reg.setPassword("testPass123");
        reg.setFirstName("Lift");
        reg.setLastName("Tester");
        reg.setWeight(80.0);
        reg.setHeight(180.0);
        reg.setAge(30);
        HttpResponse<String> regResp = send(post("/api/auth/register", reg));
        assertThat(regResp.statusCode()).isEqualTo(200);
        userId = json(regResp).get("id").asText();

        LoginRequest login = new LoginRequest();
        login.setEmail("exercisetest@test.com");
        login.setPassword("testPass123");
        jwt = json(send(post("/api/auth/login", login))).get("accessToken").asText();
        assertThat(jwt).isNotBlank();
    }

    // ── Logging ──────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("A lifting session round-trips its movements, volume and 1RM estimates")
    void logsExercises() throws Exception {
        ActivityRequest request = liftingRequest(LocalDateTime.now().minusDays(14),
                exercise("Bench Press", 3, 8, 60.0),
                exercise("Squat", 5, 5, 100.0));

        HttpResponse<String> response = send(postAuth("/api/activities", request));
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode body = json(response);
        liftingActivityId = body.get("id").asText();

        JsonNode exercises = body.get("exercises");
        assertThat(exercises).hasSize(2);
        assertThat(exercises.get(0).get("name").asText()).isEqualTo("Bench Press");
        assertThat(exercises.get(0).get("volume").asDouble()).isEqualTo(1440.0);
        assertThat(exercises.get(0).get("estimatedOneRepMax").asDouble()).isEqualTo(76.0);
        assertThat(exercises.get(1).get("name").asText()).isEqualTo("Squat");

        // The order they were entered in survives the round trip.
        assertThat(exercises.get(0).get("orderIndex").asInt()).isZero();
        assertThat(exercises.get(1).get("orderIndex").asInt()).isEqualTo(1);

        assertThat(body.get("totalVolume").asDouble()).isEqualTo(3940.0);
    }

    @Test
    @Order(2)
    @DisplayName("The first session of a movement is a benchmark, a heavier one is a record")
    void secondSessionBreaksRecord() throws Exception {
        HttpResponse<String> response = send(postAuth("/api/activities",
                liftingRequest(LocalDateTime.now().minusDays(7),
                        exercise("Bench Press", 3, 8, 70.0))));
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode records = json(response).get("exercisePersonalBests");
        assertThat(records).isNotNull();
        assertThat(records.get(0).asText()).contains("Bench Press").contains("70kg x 8");
    }

    @Test
    @Order(3)
    @DisplayName("Per-movement records are exposed, ranked by estimated 1RM")
    void exposesExercisePersonalBests() throws Exception {
        HttpResponse<String> response = send(getAuth("/api/exercises/personal-bests/" + userId));
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode records = json(response);
        assertThat(records).isNotEmpty();
        // Squat at 100x5 estimates higher than bench at 70x8, so it leads.
        assertThat(records.get(0).get("displayName").asText()).isEqualTo("Squat");
        assertThat(records.get(0).get("bestWeightKg").asDouble()).isEqualTo(100.0);

        JsonNode bench = null;
        for (JsonNode r : records) {
            if ("bench press".equals(r.get("normalisedName").asText())) bench = r;
        }
        assertThat(bench).isNotNull();
        assertThat(bench.get("bestWeightKg").asDouble()).isEqualTo(70.0);
    }

    @Test
    @Order(4)
    @DisplayName("Another user's exercise records are 403, not someone else's numbers")
    void exercisePersonalBestsAreOwnershipChecked() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/exercises/personal-bests/" + userId)))
                .header("Authorization", "Bearer " + attackerJwt())
                .GET().build());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).doesNotContain("Bench Press");
    }

    // ── Distance ─────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("Distance sent as a field is stored and becomes a record")
    void distanceIsAFirstClassField() throws Exception {
        ActivityRequest run = new ActivityRequest();
        run.setType(ActivityType.RUNNING);
        run.setDuration(30);
        run.setDistanceKm(5.0);
        run.setStartTime(LocalDateTime.now().minusDays(3));

        JsonNode body = json(send(postAuth("/api/activities", run)));
        assertThat(body.get("distanceKm").asDouble()).isEqualTo(5.0);

        // Further run → the message names distance, which it could not do while the
        // number lived in a JSON blob (PLAN P0-2).
        ActivityRequest longer = new ActivityRequest();
        longer.setType(ActivityType.RUNNING);
        longer.setDuration(20);            // shorter session
        longer.setDistanceKm(9.0);         // but further
        longer.setStartTime(LocalDateTime.now().minusDays(2));

        JsonNode second = json(send(postAuth("/api/activities", longer)));
        assertThat(second.get("isPersonalBest").asBoolean()).isTrue();
        assertThat(second.get("personalBestMessage").asText()).contains("furthest at 9 km");
    }

    @Test
    @Order(11)
    @DisplayName("Distance inside additionalMetrics is still accepted and promoted")
    void distanceFromLegacyMetricsBlob() throws Exception {
        ActivityRequest run = new ActivityRequest();
        run.setType(ActivityType.CYCLING);
        run.setDuration(45);
        run.setStartTime(LocalDateTime.now().minusDays(1));
        run.setAdditionalMetrics(Map.of("distance_km", 22.5));

        JsonNode body = json(send(postAuth("/api/activities", run)));

        // Old clients and the Gemini parser both still send it this way.
        assertThat(body.get("distanceKm").asDouble()).isEqualTo(22.5);
    }

    // ── Edit and delete ──────────────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("Editing replaces the movement list rather than appending to it")
    void editReplacesExercises() throws Exception {
        ActivityRequest edit = new ActivityRequest();
        edit.setExercises(List.of(exercise("Bench Press", 4, 6, 72.5)));

        HttpResponse<String> response = send(put("/api/activities/" + liftingActivityId, edit));
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode exercises = json(response).get("exercises");
        assertThat(exercises).hasSize(1);
        assertThat(exercises.get(0).get("weightKg").asDouble()).isEqualTo(72.5);
    }

    @Test
    @Order(21)
    @DisplayName("An edit that removes the record-setting set lowers the record")
    void editRecalculatesExerciseRecords() throws Exception {
        // The 100kg squat was in the activity that test 20 just rewrote, so nothing
        // supports a squat record any more and it must be gone, not merely stale.
        JsonNode records = json(send(getAuth("/api/exercises/personal-bests/" + userId)));

        for (JsonNode r : records) {
            assertThat(r.get("normalisedName").asText()).isNotEqualTo("squat");
        }
    }

    @Test
    @Order(22)
    @DisplayName("Omitting exercises on an edit leaves the existing ones alone")
    void editWithoutExercisesKeepsThem() throws Exception {
        ActivityRequest edit = new ActivityRequest();
        edit.setNotes("just a note");

        JsonNode body = json(send(put("/api/activities/" + liftingActivityId, edit)));

        assertThat(body.get("notes").asText()).isEqualTo("just a note");
        assertThat(body.get("exercises")).hasSize(1);
    }

    @Test
    @Order(23)
    @DisplayName("Deleting the record-setting workout lowers the record to the best that survives")
    void deleteRecalculatesExerciseRecords() throws Exception {
        // This activity currently holds the bench record at 72.5kg (set by the edit in
        // test 20). An earlier, separate session logged 70kg and is untouched — so the
        // record must drop to 70, not vanish and not stay at 72.5.
        HttpResponse<String> deleteResp = send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/activities/" + liftingActivityId)))
                .header("Authorization", "Bearer " + jwt)
                .DELETE().build());
        assertThat(deleteResp.statusCode()).isEqualTo(204);

        JsonNode records = json(send(getAuth("/api/exercises/personal-bests/" + userId)));

        JsonNode bench = null;
        for (JsonNode r : records) {
            if ("bench press".equals(r.get("normalisedName").asText())) bench = r;
        }
        assertThat(bench).as("the surviving 70kg session still supports a record").isNotNull();
        assertThat(bench.get("bestWeightKg").asDouble()).isEqualTo(70.0);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ExerciseRequest exercise(String name, int sets, int reps, Double weight) {
        return new ExerciseRequest(name, sets, reps, weight);
    }

    private ActivityRequest liftingRequest(LocalDateTime when, ExerciseRequest... exercises) {
        ActivityRequest request = new ActivityRequest();
        request.setType(ActivityType.WEIGHT_TRAINING);
        request.setDuration(50);
        request.setStartTime(when);
        request.setExercises(List.of(exercises));
        return request;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return objectMapper.readTree(response.body());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest post(String path, Object body) throws Exception {
        return HttpRequest.newBuilder()
                .uri(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
    }

    private HttpRequest postAuth(String path, Object body) throws Exception {
        return HttpRequest.newBuilder()
                .uri(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + jwt)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
    }

    private HttpRequest put(String path, Object body) throws Exception {
        return HttpRequest.newBuilder()
                .uri(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + jwt)
                .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
    }

    private HttpRequest getAuth(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url(path)))
                .header("Authorization", "Bearer " + jwt)
                .GET().build();
    }

    /** Registers (once) and logs in a second account, for the cross-user check. */
    private String attackerJwt() throws Exception {
        RegisterRequest reg = new RegisterRequest();
        reg.setEmail("exercise-attacker@test.com");
        reg.setPassword("testPass123");
        reg.setFirstName("Nosy");
        reg.setLastName("Lifter");
        send(post("/api/auth/register", reg));

        LoginRequest login = new LoginRequest();
        login.setEmail("exercise-attacker@test.com");
        login.setPassword("testPass123");
        return json(send(post("/api/auth/login", login))).get("accessToken").asText();
    }
}
