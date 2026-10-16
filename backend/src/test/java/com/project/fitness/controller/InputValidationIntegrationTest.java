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
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — values that make no sense are refused, not stored (PLAN P4-17).
 *
 * <p>Pain severity, water, sleep hours, the activity difficulty rating and the profile's
 * weight, height and age all accepted anything: a negative drink, a 40-hour night, a
 * severity of 90, a weight of -5 kg. They fed averages, goals, BMI and the digest.
 * Sleep quality was worse: it was silently clamped into 1-5, so a missing value was
 * saved as a 1 and the client was never told. Each is now a 400 that names the limit.
 *
 * <p>Per AGENT.md §7, plain java.net.http.HttpClient — not TestRestTemplate.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Input Validation Integration Tests")
class InputValidationIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();

    private String jwt, userId;

    @BeforeAll
    void setUp() throws Exception {
        userId = json(send(request("POST", "/api/auth/register", Map.of(
                "email", "validation@test.com", "password", "testPass123",
                "firstName", "Val", "lastName", "Idation", "weight", 70.0, "height", 175.0, "age", 30), null)))
                .get("id").asText();
        jwt = json(send(request("POST", "/api/auth/login",
                Map.of("email", "validation@test.com", "password", "testPass123"), null)))
                .get("accessToken").asText();
    }

    // ── Profile, registration and metrics ───────────────────────────────────

    @Test
    @DisplayName("Profile weight, height and age must be believable")
    void profileRanges() throws Exception {
        assertRejected(put("/api/profile/" + userId, Map.of("weight", -5.0)), "weight");
        assertRejected(put("/api/profile/" + userId, Map.of("weight", 0)), "weight");
        assertRejected(put("/api/profile/" + userId, Map.of("weight", 9000)), "weight");
        assertRejected(put("/api/profile/" + userId, Map.of("height", 0)), "height");
        assertRejected(put("/api/profile/" + userId, Map.of("height", 900)), "height");
        assertRejected(put("/api/profile/" + userId, Map.of("age", -1)), "age");
        assertRejected(put("/api/profile/" + userId, Map.of("age", 200)), "age");

        assertThat(send(put("/api/profile/" + userId,
                Map.of("weight", 72.5, "height", 178.0, "age", 31))).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("A refused profile update changes nothing")
    void refusedUpdateIsAtomic() throws Exception {
        // A good weight alongside a bad age: neither may be saved.
        assertRejected(put("/api/profile/" + userId, Map.of("weight", 99.0, "age", 500)), "age");

        JsonNode profile = json(send(get("/api/profile/" + userId)));
        assertThat(profile.get("weight").asDouble()).isNotEqualTo(99.0);
    }

    @Test
    @DisplayName("Registration applies the same limits")
    void registrationRanges() throws Exception {
        assertRejected(request("POST", "/api/auth/register", Map.of(
                "email", "badweight@test.com", "password", "testPass123", "weight", -1.0), null), "weight");
        assertRejected(request("POST", "/api/auth/register", Map.of(
                "email", "badage@test.com", "password", "testPass123", "age", 999), null), "age");
    }

    @Test
    @DisplayName("Saving body metrics can't be used to get around them")
    void metricsRanges() throws Exception {
        assertRejected(put("/api/health/metrics/" + userId, Map.of("weight", 0)), "weight");
        assertRejected(put("/api/health/metrics/" + userId, Map.of("weight", -3.0)), "weight");
        assertRejected(put("/api/health/metrics/" + userId, Map.of("height", 5000)), "height");
    }

    // ── Pain, water, sleep, activity ────────────────────────────────────────

    @Test
    @DisplayName("Pain severity is 1 to 5")
    void painSeverity() throws Exception {
        for (int bad : new int[] {0, 6, -1, 90}) {
            assertRejected(post("/api/pain-log", Map.of("bodyPart", "LEFT_KNEE", "severity", bad)), "severity");
        }
        assertThat(send(post("/api/pain-log", Map.of("bodyPart", "LEFT_KNEE", "severity", 3))).statusCode())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("A water entry is a positive, believable amount, and must be sent")
    void waterAmount() throws Exception {
        assertRejected(post("/api/health/water/" + userId, Map.of("amountMl", -250)), "amount");
        assertRejected(post("/api/health/water/" + userId, Map.of("amountMl", 0)), "amount");
        assertRejected(post("/api/health/water/" + userId, Map.of("amountMl", 99999)), "amount");
        assertRejected(post("/api/health/water/" + userId, Map.of()), "amount");   // used to log 0

        assertThat(send(post("/api/health/water/" + userId, Map.of("amountMl", 250))).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("Sleep hours are within a day, and quality is 1 to 5, not silently clamped")
    void sleepRanges() throws Exception {
        assertRejected(post("/api/health/sleep/" + userId, sleep(0, 3)), "hours");
        assertRejected(post("/api/health/sleep/" + userId, sleep(-2, 3)), "hours");
        assertRejected(post("/api/health/sleep/" + userId, sleep(25, 3)), "hours");
        assertRejected(post("/api/health/sleep/" + userId, sleep(7, 0)), "quality");   // clamped to 1 before
        assertRejected(post("/api/health/sleep/" + userId, sleep(7, 9)), "quality");   // clamped to 5 before

        assertThat(send(post("/api/health/sleep/" + userId, sleep(7.5, 4))).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("An activity's difficulty rating is 1 to 5 when it is given")
    void activityDifficulty() throws Exception {
        for (int bad : new int[] {0, 6, -3}) {
            assertRejected(post("/api/activities", activity(bad)), "rating");
        }
        assertThat(send(post("/api/activities", activity(3))).statusCode()).isEqualTo(200);

        Map<String, Object> unrated = activity(3);
        unrated.remove("difficultyRating");
        assertThat(send(post("/api/activities", unrated)).statusCode()).isEqualTo(200);   // it is optional
    }

    /**
     * Found by the live QA pass: -30 minutes, -500 kcal and 2,000,000,000 kcal were all
     * saved with a 200, and the calorie figure feeds the CALORIES_BURNED leaderboard.
     */
    @Test
    @DisplayName("An activity's duration, calories, distance and lifts must be believable")
    void activityNumbers() throws Exception {
        assertRejected(post("/api/activities", withField("duration", -30)), "duration");
        assertRejected(post("/api/activities", withField("duration", 0)), "duration");
        assertRejected(post("/api/activities", withField("duration", 100_000)), "duration");
        assertRejected(post("/api/activities", withField("caloriesBurned", -500)), "calories");
        assertRejected(post("/api/activities", withField("caloriesBurned", 2_000_000_000)), "calories");
        assertRejected(post("/api/activities", withField("distanceKm", -5.0)), "distance");
        assertRejected(post("/api/activities", withField("additionalMetrics", Map.of("distance_km", 99_999))), "distance");
        assertRejected(post("/api/activities", withField("exercises",
                java.util.List.of(Map.of("name", "Bench", "sets", 3, "reps", 8, "weightKg", -60.0)))), "weight");
        assertRejected(post("/api/activities", withField("exercises",
                java.util.List.of(Map.of("name", "Bench", "sets", 0, "reps", 8)))), "sets");

        assertThat(send(post("/api/activities", withField("caloriesBurned", 650))).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("An edit is held to the same limits, and a refused edit changes nothing")
    void activityEditNumbers() throws Exception {
        String id = json(send(post("/api/activities", activity(3)))).get("id").asText();
        assertRejected(put("/api/activities/" + id, Map.of("duration", -10)), "duration");
        assertRejected(put("/api/activities/" + id, Map.of("caloriesBurned", 5_000_000, "notes", "kept?")), "calories");

        JsonNode list = json(send(get("/api/activities?size=100")));
        for (JsonNode a : list.get("content")) {
            if (a.get("id").asText().equals(id)) {
                assertThat(a.get("duration").asInt()).isEqualTo(30);
                assertThat(a.get("notes").isNull() || !a.get("notes").asText().equals("kept?")).isTrue();
            }
        }
    }

    /** Notes past the column length used to fail at the database, as a 409 "data conflict, refresh and try again". */
    @Test
    @DisplayName("Notes over 1000 characters are a 400 that says so, on every path that writes them")
    void activityNotesLength() throws Exception {
        String longNotes = "n".repeat(1001);
        assertRejected(post("/api/activities", withField("notes", longNotes)), "1000 characters");
        String id = json(send(post("/api/activities", activity(3)))).get("id").asText();
        assertRejected(put("/api/activities/" + id, Map.of("notes", longNotes)), "1000 characters");
        assertRejected(put("/api/activities/" + id + "/notes", Map.of("notes", longNotes)), "1000 characters");

        assertThat(send(put("/api/activities/" + id + "/notes", Map.of("notes", "n".repeat(1000)))).statusCode())
                .isEqualTo(200);
    }

    /**
     * Text past a 255-character column (names, pain and sleep notes) reached the client
     * as a 409 "data conflict, refresh and try again", which no refresh could fix.
     */
    @Test
    @DisplayName("Text too long for its column is a 400 that names the field, not a 409 'data conflict'")
    void overlongTextNamesTheField() throws Exception {
        String tooLong = "x".repeat(300);
        assertRejected(request("POST", "/api/auth/register", Map.of(
                "email", "longname@test.com", "password", "testPass123", "firstName", tooLong), null), "first name");
        assertRejected(put("/api/profile/" + userId, Map.of("lastName", tooLong)), "last name");
        assertRejected(post("/api/pain-log", Map.of("bodyPart", "NECK", "severity", 2, "description", tooLong)),
                "description");
        assertRejected(post("/api/health/sleep/" + userId, Map.of(
                "hoursSlept", 7, "qualityRating", 3, "sleepDate", "2026-01-10", "notes", tooLong)), "notes");
    }

    /** Found by the live QA pass: a 10,000,000 kcal meal was saved with a 200. */
    @Test
    @DisplayName("A meal's calories and macros must be believable")
    void mealNumbers() throws Exception {
        assertRejected(post("/api/nutrition/meals", Map.of("calories", 10_000_000)), "calories");
        assertRejected(post("/api/nutrition/meals", Map.of("proteinGrams", 50_000.0)), "protein");
        assertRejected(post("/api/nutrition/meals", Map.of("calories", 300, "fatGrams", -10.0)), "fat");
        assertThat(send(post("/api/nutrition/meals", Map.of("calories", 650))).statusCode()).isEqualTo(200);
    }

    /** Found by the live QA pass: a night in 2099, and waking before bedtime, were both saved. */
    @Test
    @DisplayName("Sleep can't be in the future, and waking comes after going to bed")
    void sleepDates() throws Exception {
        assertRejected(post("/api/health/sleep/" + userId, Map.of(
                "hoursSlept", 7, "qualityRating", 3, "sleepDate", "2099-01-01")), "hasn't happened");
        assertRejected(post("/api/health/sleep/" + userId, Map.of(
                "hoursSlept", 7, "qualityRating", 3, "sleepDate", "2026-01-12",
                "bedtime", "2026-01-12T23:00:00", "wakeTime", "2026-01-12T06:00:00")), "wake time");
        assertThat(send(post("/api/health/sleep/" + userId, Map.of(
                "hoursSlept", 7, "qualityRating", 3, "sleepDate", "2026-01-12",
                "bedtime", "2026-01-12T23:00:00", "wakeTime", "2026-01-13T06:00:00"))).statusCode()).isEqualTo(200);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> withField(String key, Object value) {
        Map<String, Object> body = activity(3);
        body.remove("difficultyRating");
        body.put(key, value);
        return body;
    }

    private Map<String, Object> sleep(double hours, int quality) {
        return Map.of("hoursSlept", hours, "qualityRating", quality, "sleepDate", "2026-01-15");
    }

    private Map<String, Object> activity(int difficulty) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", "RUNNING");
        body.put("duration", 30);
        body.put("startTime", LocalDateTime.now().toString());
        body.put("difficultyRating", difficulty);
        return body;
    }

    private void assertRejected(HttpRequest r, String mentions) throws Exception {
        HttpResponse<String> response = send(r);
        assertThat(response.statusCode()).as(r.method() + " " + r.uri().getPath() + " " + response.body())
                .isEqualTo(400);
        assertThat(json(response).get("error").asText().toLowerCase()).contains(mentions);
    }

    private String url(String path) { return "http://localhost:" + port + path; }

    private JsonNode json(HttpResponse<String> r) throws Exception { return objectMapper.readTree(r.body()); }

    private HttpResponse<String> send(HttpRequest r) throws Exception {
        return http.send(r, HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(String method, String path, Object body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return b.build();
    }

    private HttpRequest post(String path, Object body) throws Exception { return request("POST", path, body, jwt); }

    private HttpRequest put(String path, Object body) throws Exception { return request("PUT", path, body, jwt); }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder().uri(URI.create(url(path)))
                .header("Authorization", "Bearer " + jwt).GET().build();
    }
}
