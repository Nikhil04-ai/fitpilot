package com.project.fitness.controller;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof for the PLAN.md P0 fixes, against a real database.
 *
 * <p>The other integration tests assert that the new fields <em>exist</em> on the
 * response. That is not the same as proving the behaviour behind them works: a field
 * that is always null passes a "has this key" check. These tests assert the values.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("P0 Verification Tests")
class P0VerificationIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();
    private String jwt;
    private String userId;

    @BeforeAll
    void setUp() throws Exception {
        RegisterRequest reg = new RegisterRequest();
        reg.setEmail("p0verify@test.com");
        reg.setPassword("testPass123");
        reg.setFirstName("Ver");
        reg.setLastName("Ify");
        reg.setWeight(75.0);
        reg.setHeight(175.0);
        reg.setAge(25);
        HttpResponse<String> r = send(post("/api/auth/register", reg));
        userId = json(r).get("id").asText();

        LoginRequest login = new LoginRequest();
        login.setEmail("p0verify@test.com");
        login.setPassword("testPass123");
        jwt = json(send(post("/api/auth/login", login))).get("accessToken").asText();
    }

    // ── P0-3: the PR badge must actually become true, not merely exist ────────

    @Test
    @DisplayName("P0-3: the record-holding activity comes back with isPersonalBest=true")
    void personalBestFlagIsActuallySet() throws Exception {
        log(ActivityType.SWIMMING, 20, LocalDateTime.now().minusDays(3));
        String longestId = id(log(ActivityType.SWIMMING, 95, LocalDateTime.now().minusDays(2)));
        log(ActivityType.SWIMMING, 25, LocalDateTime.now().minusDays(1));

        JsonNode list = json(send(get("/api/activities?page=0&size=100"))).get("content");

        JsonNode longest = null;
        for (JsonNode n : list) {
            if (n.get("id").asText().equals(longestId)) longest = n;
        }
        assertThat(longest).isNotNull();
        // The whole point of P0-3: this is the field the badge reads.
        assertThat(longest.get("isPersonalBest").asBoolean())
                .as("the 95-minute swim holds the record and must be flagged")
                .isTrue();
    }

    // ── P0-1: a progression suggestion must actually be produced ─────────────

    @Test
    @DisplayName("P0-1: three easy sessions of a type produce a real progression suggestion")
    void progressionSuggestionIsActuallyProduced() throws Exception {
        String a = id(log(ActivityType.HIIT, 30, LocalDateTime.now().minusDays(3)));
        String b = id(log(ActivityType.HIIT, 30, LocalDateTime.now().minusDays(2)));
        String c = id(log(ActivityType.HIIT, 30, LocalDateTime.now().minusDays(1)));

        rate(a, 1);
        rate(b, 1);
        JsonNode last = json(rate(c, 1));   // third rating completes the window

        assertThat(last.get("progressionSuggestion").isNull())
                .as("ProgressionService should fire once three rated sessions exist")
                .isFalse();
        assertThat(last.get("progressionSuggestion").asText().toLowerCase())
                .contains("easy");
    }

    // ── P0-4: deleting the record-setter must actually lower the record ──────

    @Test
    @DisplayName("P0-4: deleting the record-setting activity lowers the stored personal best")
    void deletingRecordSetterLowersPersonalBest() throws Exception {
        log(ActivityType.CYCLING, 40, LocalDateTime.now().minusDays(2));
        String monsterId = id(log(ActivityType.CYCLING, 200, LocalDateTime.now().minusDays(1)));

        JsonNode before = personalBestFor("CYCLING");
        assertThat(before.get("bestDuration").asInt()).isEqualTo(200);

        send(delete("/api/activities/" + monsterId));

        JsonNode after = personalBestFor("CYCLING");
        assertThat(after.get("bestDuration").asInt())
                .as("the 200-minute ride is gone, so the record must fall back to 40")
                .isEqualTo(40);
    }

    @Test
    @DisplayName("P0-4: deleting a mid-streak day actually shortens the streak")
    void deletingMidStreakDayBreaksStreak() throws Exception {
        // A fresh account, so the streak maths isn't polluted by the other tests.
        RegisterRequest reg = new RegisterRequest();
        reg.setEmail("streakverify@test.com");
        reg.setPassword("testPass123");
        reg.setFirstName("Streak");
        reg.setLastName("Check");
        reg.setWeight(70.0);
        send(post("/api/auth/register", reg));
        LoginRequest login = new LoginRequest();
        login.setEmail("streakverify@test.com");
        login.setPassword("testPass123");
        JsonNode auth = json(send(post("/api/auth/login", login)));
        String streakJwt = auth.get("accessToken").asText();
        String streakUserId = auth.get("user").get("id").asText();

        // Three consecutive days → streak of 3.
        logAs(streakJwt, ActivityType.RUNNING, 30, LocalDateTime.now().minusDays(2));
        String middle = id(logAs(streakJwt, ActivityType.RUNNING, 30, LocalDateTime.now().minusDays(1)));
        logAs(streakJwt, ActivityType.RUNNING, 30, LocalDateTime.now());

        assertThat(profileStreak(streakJwt, streakUserId)).isEqualTo(3);

        // Remove yesterday — today is now an island.
        send(HttpRequest.newBuilder().uri(URI.create(url("/api/activities/" + middle)))
                .header("Authorization", "Bearer " + streakJwt).DELETE().build());

        assertThat(profileStreak(streakJwt, streakUserId))
                .as("with yesterday deleted the streak cannot still be 3")
                .isEqualTo(1);
    }

    // ── P0-5: completedAt must equal start + duration, not merely be non-null ─

    @Test
    @DisplayName("P0-5: completedAt is start + duration, not just non-null")
    void completedAtIsCorrectlyDerived() throws Exception {
        LocalDateTime start = LocalDateTime.now().minusHours(5).withNano(0);
        JsonNode body = json(log(ActivityType.YOGA, 45, start));

        assertThat(LocalDateTime.parse(body.get("completedAt").asText()))
                .isEqualTo(start.plusMinutes(45));
    }

    // ── P0-2: the parsed metrics survive a round trip ────────────────────────

    @Test
    @DisplayName("P0-2: additionalMetrics come back on the list endpoint, not just on create")
    void additionalMetricsSurviveRoundTrip() throws Exception {
        ActivityRequest req = new ActivityRequest();
        req.setType(ActivityType.WEIGHT_TRAINING);
        req.setDuration(50);
        req.setStartTime(LocalDateTime.now());
        req.setAdditionalMetrics(Map.of("distance_km", 5, "sets", 3, "reps", 12));
        String createdId = id(send(postAuth("/api/activities", req)));

        JsonNode list = json(send(get("/api/activities?page=0&size=100"))).get("content");
        JsonNode found = null;
        for (JsonNode n : list) {
            if (n.get("id").asText().equals(createdId)) found = n;
        }
        assertThat(found).isNotNull();
        // This is what the frontend's metricChips() renders. Before P0-2 the value was
        // stored but no caller ever looked at it.
        assertThat(found.get("additionalMetrics").get("sets").asInt()).isEqualTo(3);
        assertThat(found.get("additionalMetrics").get("reps").asInt()).isEqualTo(12);
    }

    // ── P0-7: an expired goal must stop accruing ─────────────────────────────

    @Test
    @DisplayName("P0-7: a goal past its deadline reports isExpired and stops accruing")
    void expiredGoalStopsAccruing() throws Exception {
        String body = "{\"type\":\"WORKOUTS_PER_WEEK\",\"targetValue\":50,\"deadline\":\""
                + java.time.LocalDate.now().minusDays(3) + "\"}";
        send(HttpRequest.newBuilder().uri(URI.create(url("/api/goals")))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + jwt)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());

        log(ActivityType.WALKING, 30, LocalDateTime.now());

        JsonNode goals = json(send(get("/api/goals/" + userId)));
        JsonNode expired = null;
        for (JsonNode g : goals) {
            if (g.get("targetValue").asDouble() == 50.0) expired = g;
        }
        assertThat(expired).isNotNull();
        assertThat(expired.get("isExpired").asBoolean()).isTrue();
        assertThat(expired.get("currentValue").asDouble())
                .as("an expired goal must not keep counting new workouts")
                .isZero();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String url(String path) { return "http://localhost:" + port + path; }

    private HttpResponse<String> send(HttpRequest req) throws Exception {
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        return objectMapper.readTree(r.body());
    }

    private String id(HttpResponse<String> r) throws Exception { return json(r).get("id").asText(); }

    private HttpRequest post(String path, Object body) throws Exception {
        return HttpRequest.newBuilder().uri(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
    }

    private HttpRequest postAuth(String path, Object body) throws Exception {
        return HttpRequest.newBuilder().uri(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + jwt)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
    }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder().uri(URI.create(url(path)))
                .header("Authorization", "Bearer " + jwt).GET().build();
    }

    private HttpRequest delete(String path) {
        return HttpRequest.newBuilder().uri(URI.create(url(path)))
                .header("Authorization", "Bearer " + jwt).DELETE().build();
    }

    private HttpResponse<String> log(ActivityType type, int minutes, LocalDateTime start) throws Exception {
        ActivityRequest req = new ActivityRequest();
        req.setType(type);
        req.setDuration(minutes);
        req.setStartTime(start);
        return send(postAuth("/api/activities", req));
    }

    private HttpResponse<String> logAs(String token, ActivityType type, int minutes,
                                       LocalDateTime start) throws Exception {
        ActivityRequest req = new ActivityRequest();
        req.setType(type);
        req.setDuration(minutes);
        req.setStartTime(start);
        return send(HttpRequest.newBuilder().uri(URI.create(url("/api/activities")))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(req)))
                .build());
    }

    private HttpResponse<String> rate(String activityId, int rating) throws Exception {
        return send(postAuth("/api/activities/" + activityId + "/rate", Map.of("rating", rating)));
    }

    private JsonNode personalBestFor(String type) throws Exception {
        JsonNode all = json(send(get("/api/personal-bests/" + userId)));
        for (JsonNode pb : all) {
            if (pb.get("activityType").asText().equals(type)) return pb;
        }
        throw new AssertionError("no personal best recorded for " + type);
    }

    private int profileStreak(String token, String id) throws Exception {
        HttpResponse<String> r = send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/profile/" + id)))
                .header("Authorization", "Bearer " + token).GET().build());
        return json(r).get("currentStreak").asInt();
    }
}
