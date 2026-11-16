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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — the behaviour the full-app test run of 2026-11-16 found wrong.
 *
 * <p>Eight agents drove every feature through the API and a real browser. Each test here
 * replays one of their reported scenarios, and fails on the code as it was before the fix.
 * The input limits found by the same run are in InputValidationIntegrationTest, the auth
 * edge cases in AuthEdgeCasesIntegrationTest, goals in GoalLifecycleIntegrationTest and
 * body weight in BodyWeightIntegrationTest.
 *
 * <p>Per AGENT.md §7, plain java.net.http.HttpClient — not TestRestTemplate.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Full-app run regressions (2026-11-16)")
class FullAppRunRegressionIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newHttpClient();
    private String jwt, userId;

    @BeforeEach
    void freshUser() throws Exception {
        String[] u = register("Reg");
        userId = u[0];
        jwt = u[1];
    }

    @Test
    @DisplayName("A workout edit can clear its notes and distance, and clear wins over a value sent with it")
    void activityEditClearsNotesAndDistance() throws Exception {
        String id = json(ok(call("POST", "/api/activities", Map.of("type", "WEIGHT_TRAINING", "duration", 45,
                "distanceKm", 2.5, "notes", "Strong day"), jwt))).get("id").asText();

        ok(call("PUT", "/api/activities/" + id, Map.of("notes", "kept?", "clear", List.of("notes", "distanceKm")), jwt));
        JsonNode a = activity(id);
        assertThat(a.get("notes").isNull()).as(a.toString()).isTrue();
        assertThat(a.get("distanceKm").isNull()).as(a.toString()).isTrue();

        HttpResponse<String> bad = call("PUT", "/api/activities/" + id, Map.of("clear", List.of("duration")), jwt);
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(bad.body()).contains("Can't clear 'duration'");
    }

    @Test
    @DisplayName("Backdated workouts extend the streak as they are logged, in any order")
    void backdatedWorkoutsExtendTheStreak() throws Exception {
        int streak = 0;
        for (int daysAgo = 0; daysAgo <= 2; daysAgo++) {    // today, then yesterday, then the day before
            streak = json(ok(call("POST", "/api/activities", Map.of("type", "RUNNING", "duration", 20,
                    "startTime", LocalDateTime.now().minusDays(daysAgo).withNano(0).toString()), jwt)))
                    .get("currentStreak").asInt();
        }
        assertThat(streak).isEqualTo(3);
        assertThat(json(ok(call("GET", "/api/stats/all-time/" + userId, null, jwt))).get("currentStreak").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("The active plan says when today's day is already done")
    void planKnowsTodayIsDone() throws Exception {
        String planId = json(ok(call("GET", "/api/plans", null, jwt))).get(0).get("id").asText();
        ok(call("POST", "/api/plans/" + planId + "/enroll", null, jwt));
        JsonNode active = json(ok(call("GET", "/api/plans/active/" + userId, null, jwt)));
        assertThat(active.get("completedToday").asBoolean()).isFalse();
        // F35: the enrollment names its plan, so the app can show that plan's schedule.
        assertThat(active.path("planId").asText()).isEqualTo(planId);

        JsonNode done = json(ok(call("POST", "/api/plans/active/" + userId + "/complete-day", null, jwt)));
        assertThat(done.get("completedToday").asBoolean()).isTrue();
        assertThat(done.get("lastCompletedDate").asText()).isEqualTo(LocalDate.now().toString());
        assertThat(json(ok(call("GET", "/api/plans/active/" + userId, null, jwt))).get("completedToday").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("A pain entry linked to a workout says which one, and outlives it unlinked (F20)")
    void painNamesItsWorkout() throws Exception {
        String act = json(ok(call("POST", "/api/activities", Map.of("type", "RUNNING", "duration", 30), jwt))).get("id").asText();

        JsonNode logged = json(ok(call("POST", "/api/pain-log",
                Map.of("activityId", act, "bodyPart", "LEFT_KNEE", "severity", 3), jwt)));
        assertThat(logged.path("activity").path("id").asText()).as(logged.toString()).isEqualTo(act);
        assertThat(logged.path("activity").path("type").asText()).isEqualTo("RUNNING");
        assertThat(logged.path("activity").hasNonNull("startTime")).isTrue();

        JsonNode unlinked = json(ok(call("POST", "/api/pain-log", Map.of("bodyPart", "NECK", "severity", 1), jwt)));
        assertThat(unlinked.get("activity").isNull()).as(unlinked.toString()).isTrue();

        JsonNode history = json(ok(call("GET", "/api/pain-log/" + userId, null, jwt))).get("content");
        assertThat(history).hasSize(2);
        JsonNode knee = history.get(0).get("bodyPart").asText().equals("LEFT_KNEE") ? history.get(0) : history.get(1);
        assertThat(knee.path("activity").path("id").asText()).isEqualTo(act);

        assertThat(call("DELETE", "/api/activities/" + act, null, jwt).statusCode()).isBetween(200, 204);
        JsonNode after = json(ok(call("GET", "/api/pain-log/" + userId, null, jwt))).get("content");
        assertThat(after).hasSize(2);
        after.forEach(p -> assertThat(p.get("activity").isNull()).as(p.toString()).isTrue());
    }

    @Test
    @DisplayName("A day with nothing eaten has no net balance, not a full-maintenance deficit")
    void emptyDayHasNoBalance() throws Exception {
        JsonNode empty = json(ok(call("GET", "/api/nutrition/summary", null, jwt)));
        assertThat(empty.get("mealsLogged").asInt()).isZero();
        assertThat(empty.get("netCalories").isNull()).as(empty.toString()).isTrue();
        assertThat(empty.get("balance").isNull()).isTrue();

        ok(call("POST", "/api/nutrition/meals", Map.of("calories", 500), jwt));
        JsonNode eaten = json(ok(call("GET", "/api/nutrition/summary", null, jwt)));
        assertThat(eaten.get("netCalories").isNumber()).as(eaten.toString()).isTrue();
        assertThat(eaten.get("balance").asText()).isNotBlank();
    }

    @Test
    @DisplayName("A new meal, an edited meal and a new digest carry their real timestamps")
    void responsesCarryTimestamps() throws Exception {
        JsonNode meal = json(ok(call("POST", "/api/nutrition/meals", Map.of("calories", 420), jwt)));
        assertThat(meal.get("createdAt").isNull()).as(meal.toString()).isFalse();
        assertThat(meal.get("updatedAt").isNull()).isFalse();

        Thread.sleep(30);
        JsonNode edited = json(ok(call("PUT", "/api/nutrition/meals/" + meal.get("id").asText(), Map.of("calories", 430), jwt)));
        assertThat(edited.get("updatedAt").asText()).isNotEqualTo(meal.get("updatedAt").asText());

        ok(call("POST", "/api/activities", Map.of("type", "RUNNING", "duration", 30), jwt));
        String monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
        JsonNode digest = json(ok(call("POST", "/api/digest/generate?weekStart=" + monday, null, jwt)));
        assertThat(digest.get("createdAt").isNull()).as(digest.toString()).isFalse();
    }

    @Test
    @DisplayName("The plateau check never says NaN% or Infinity%; 0-kcal workouts are no calorie data")
    void plateauHasNoNaNOrInfinity() throws Exception {
        for (int daysAgo : new int[] {2, 5, 16, 20}) cycle(daysAgo, 0);
        assertThat(plateau().get("status").asText()).isEqualTo("insufficient_data");

        String[] other = register("Pla");
        jwt = other[1];
        userId = other[0];
        cycle(2, 250); cycle(5, 260); cycle(16, 0); cycle(20, 0);
        JsonNode r = plateau();
        assertThat(r.toString()).doesNotContain("Infinity").doesNotContain("NaN");
        assertThat(r.get("status").asText()).isEqualTo("insufficient_data");

        String[] third = register("Plb");
        jwt = third[1];
        userId = third[0];
        cycle(2, 300); cycle(5, 300); cycle(16, 200); cycle(20, 200);
        assertThat(plateau().get("improvementPercent").asText()).isEqualTo("50.0%");
    }

    @Test
    @DisplayName("'All felt easy' only when all three sessions were rated easy")
    void progressionUsesEachRating() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int daysAgo = 3; daysAgo >= 1; daysAgo--) ids.add(cycle(daysAgo, 300));
        rate(ids.get(0), 1);
        rate(ids.get(1), 1);
        String after4 = rate(ids.get(2), 4);
        assertThat(after4).doesNotContain("all felt easy");

        String after1 = rate(ids.get(2), 1);      // now genuinely all three easy
        assertThat(after1).contains("all felt easy");
    }

    @Test
    @DisplayName("A 0 kg set is bodyweight: no 'heaviest 0 kg' or '0 kg volume' record")
    void zeroKgIsBodyweight() throws Exception {
        for (int reps : new int[] {12, 15}) {
            ok(call("POST", "/api/activities", Map.of("type", "WEIGHT_TRAINING", "duration", 20,
                    "exercises", List.of(Map.of("name", "Dip", "sets", 2, "reps", reps, "weightKg", 0))), jwt));
        }
        JsonNode record = json(ok(call("GET", "/api/exercises/personal-bests/" + userId, null, jwt))).get(0);
        assertThat(record.get("bestWeightKg").isNull()).as(record.toString()).isTrue();
        assertThat(record.get("bestWeightReps").isNull()).isTrue();
        assertThat(record.get("bestVolume").isNull()).isTrue();
        assertThat(record.get("bestBodyweightReps").asInt()).isEqualTo(15);
    }

    @Test
    @DisplayName("Deleting every workout empties Recovery, as it does the records")
    void recoveryEmptiesWhenAllWorkoutsAreDeleted() throws Exception {
        String a = json(ok(call("POST", "/api/activities", Map.of("type", "RUNNING", "duration", 30), jwt))).get("id").asText();
        String b = json(ok(call("POST", "/api/activities", Map.of("type", "RUNNING", "duration", 20), jwt))).get("id").asText();
        assertThat(json(ok(call("GET", "/api/recovery/status/" + userId, null, jwt)))).isNotEmpty();

        call("DELETE", "/api/activities/" + a, null, jwt);
        call("DELETE", "/api/activities/" + b, null, jwt);
        assertThat(json(ok(call("GET", "/api/recovery/status/" + userId, null, jwt)))).isEmpty();
    }

    @Test
    @DisplayName("A URL the firewall refuses is a 400 with the usual error body, not a 401 'log in again'")
    void oddUrlsAreBadRequests() throws Exception {
        for (String path : new String[] {"/api/activities;x=1", "/api//activities", "/api/notifications//unread-count"}) {
            HttpResponse<String> r = call("GET", path, null, jwt);
            assertThat(r.statusCode()).as(path + " " + r.body()).isEqualTo(400);
            assertThat(json(r).has("error")).as(path).isTrue();
        }
        assertThat(call("GET", "/api/notifications/unread-count", null, jwt).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("Two challenges sent at the same moment between one pair: exactly one is created")
    void simultaneousChallengesBetweenOnePair() throws Exception {
        for (int trial = 0; trial < 5; trial++) {
            String[] x = register("Rx");
            String[] y = register("Ry");
            makeFriends(x, y);

            CompletableFuture<HttpResponse<String>> fromX = http.sendAsync(request("POST", "/api/challenges",
                    Map.of("opponentIds", List.of(y[0]), "type", "WORKOUT_COUNT"), x[1]), HttpResponse.BodyHandlers.ofString());
            CompletableFuture<HttpResponse<String>> fromY = http.sendAsync(request("POST", "/api/challenges",
                    Map.of("opponentIds", List.of(x[0]), "type", "TOTAL_DURATION"), y[1]), HttpResponse.BodyHandlers.ofString());
            List<Integer> statuses = List.of(fromX.get().statusCode(), fromY.get().statusCode());

            assertThat(statuses).as("trial " + trial).containsExactlyInAnyOrder(200, 400);
            long open = 0;
            for (JsonNode c : json(ok(call("GET", "/api/challenges/user/" + x[0], null, x[1])))) {
                String status = c.get("status").asText();
                if (status.equals("PENDING") || status.equals("ACTIVE")) open++;
            }
            assertThat(open).as("open challenges between the pair, trial " + trial).isEqualTo(1);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Registers and logs in a fresh account: {id, token, email}. */
    private String[] register(String first) throws Exception {
        String email = first.toLowerCase() + "-" + UUID.randomUUID() + "@test.com";
        String id = json(ok(call("POST", "/api/auth/register", Map.of("email", email, "password", "testPass123",
                "firstName", first, "lastName", "Test", "weight", 70.0, "height", 175.0, "age", 30), null))).get("id").asText();
        String token = json(ok(call("POST", "/api/auth/login", Map.of("email", email, "password", "testPass123"), null)))
                .get("accessToken").asText();
        return new String[] {id, token, email};
    }

    private void makeFriends(String[] a, String[] b) throws Exception {
        ok(call("POST", "/api/friends/request", Map.of("email", b[2]), a[1]));
        String friendship = json(ok(call("GET", "/api/friends/invites", null, b[1]))).get(0).get("friendshipId").asText();
        ok(call("POST", "/api/friends/" + friendship + "/accept", null, b[1]));
    }

    private String cycle(int daysAgo, int kcal) throws Exception {
        return json(ok(call("POST", "/api/activities", Map.of("type", "CYCLING", "duration", 30, "caloriesBurned", kcal,
                "startTime", LocalDateTime.now().minusDays(daysAgo).withNano(0).toString()), jwt))).get("id").asText();
    }

    private JsonNode plateau() throws Exception {
        return json(ok(call("GET", "/api/stats/plateau/" + userId + "?activityType=CYCLING", null, jwt)));
    }

    private String rate(String activityId, int rating) throws Exception {
        JsonNode r = json(ok(call("POST", "/api/activities/" + activityId + "/rate", Map.of("rating", rating), jwt)));
        return r.hasNonNull("progressionSuggestion") ? r.get("progressionSuggestion").asText() : "";
    }

    private JsonNode activity(String id) throws Exception {
        for (JsonNode a : json(ok(call("GET", "/api/activities?size=100", null, jwt))).get("content")) {
            if (a.get("id").asText().equals(id)) return a;
        }
        throw new AssertionError("activity " + id + " not listed");
    }

    private HttpResponse<String> ok(HttpResponse<String> r) {
        assertThat(r.statusCode()).as(r.uri() + " " + r.body()).isEqualTo(200);
        return r;
    }

    private String url(String path) { return "http://localhost:" + port + path; }

    private JsonNode json(HttpResponse<String> r) throws Exception { return objectMapper.readTree(r.body()); }

    private HttpResponse<String> call(String method, String path, Object body, String token) throws Exception {
        return http.send(request(method, path, body, token), HttpResponse.BodyHandlers.ofString());
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
