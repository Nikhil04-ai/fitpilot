package com.project.fitness.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.fitness.dto.ActivityRequest;
import com.project.fitness.dto.ExerciseRequest;
import com.project.fitness.dto.LoginRequest;
import com.project.fitness.dto.MealRequest;
import com.project.fitness.dto.RegisterRequest;
import com.project.fitness.model.ActivityType;
import com.project.fitness.model.MealType;
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
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The weekly digest end to end (PLAN P2-3).
 *
 * <p>The test profile has no {@code GEMINI_API_KEY}, so this exercises the rule-based
 * path throughout — which is the point: the fallback has to be a real digest, not an
 * apology for a missing key.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Weekly Digest Integration Tests")
class WeeklyDigestIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();

    private String jwt;
    private LocalDate lastMonday;

    @BeforeAll
    void setupOnce() throws Exception {
        lastMonday = LocalDate.now().minusWeeks(1)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        RegisterRequest reg = new RegisterRequest();
        reg.setEmail("digesttest@test.com");
        reg.setPassword("testPass123");
        reg.setFirstName("Digest");
        reg.setLastName("Tester");
        reg.setWeight(80.0);
        reg.setHeight(180.0);
        reg.setAge(30);
        assertThat(send(post("/api/auth/register", reg)).statusCode()).isEqualTo(200);

        LoginRequest login = new LoginRequest();
        login.setEmail("digesttest@test.com");
        login.setPassword("testPass123");
        jwt = json(send(post("/api/auth/login", login))).get("accessToken").asText();

        // Two lifting sessions, a meal and a night's sleep, all inside the week under
        // review — so the digest has more than one signal to reason across.
        assertThat(send(postAuth("/api/activities",
                lifting(lastMonday, 45, "Bench Press", 3, 8, 60.0))).statusCode()).isEqualTo(200);
        assertThat(send(postAuth("/api/activities",
                lifting(lastMonday.plusDays(2), 60, "Squat", 5, 5, 100.0))).statusCode()).isEqualTo(200);

        MealRequest meal = new MealRequest();
        meal.setMealType(MealType.LUNCH);
        meal.setDescription("Chicken and rice");
        meal.setCalories(700);
        meal.setProteinGrams(50.0);
        meal.setLoggedAt(lastMonday.atTime(12, 30));
        assertThat(send(postAuth("/api/nutrition/meals", meal)).statusCode()).isEqualTo(200);
    }

    @Test
    @Order(1)
    @DisplayName("Before anything is generated, the latest digest is 204, not an error")
    void noDigestYetIsNoContent() throws Exception {
        assertThat(send(getAuth("/api/digest")).statusCode()).isEqualTo(204);
    }

    @Test
    @Order(2)
    @DisplayName("A generated digest carries the week's real figures alongside the prose")
    void digestCarriesFacts() throws Exception {
        HttpResponse<String> response = send(postAuth(
                "/api/digest/generate?weekStart=" + lastMonday, null));
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode digest = json(response);
        assertThat(digest.get("weekStart").asText()).isEqualTo(lastMonday.toString());
        assertThat(digest.get("weekEnd").asText()).isEqualTo(lastMonday.plusDays(6).toString());

        // No key in the test profile, so the rule-based path wrote this — and said so.
        assertThat(digest.get("aiGenerated").asBoolean()).isFalse();
        assertThat(digest.get("headline").asText()).isEqualTo("2 sessions, 105 minutes trained");

        JsonNode training = digest.get("dataPoints").get("training");
        assertThat(training.get("sessions").asInt()).isEqualTo(2);
        assertThat(training.get("minutes").asInt()).isEqualTo(105);
        assertThat(training.get("daysTrained").asInt()).isEqualTo(2);
        // 3x8x60 + 5x5x100 — the load the old coaching prompt never saw.
        assertThat(training.get("volumeKg").asDouble()).isEqualTo(3940.0);

        JsonNode nutrition = digest.get("dataPoints").get("nutrition");
        assertThat(nutrition.get("daysLogged").asInt()).isEqualTo(1);
        assertThat(nutrition.get("avgCaloriesPerLoggedDay").asInt()).isEqualTo(700);
    }

    @Test
    @Order(3)
    @DisplayName("The fallback digest is a real one — it observes and it advises")
    void fallbackIsStillADigest() throws Exception {
        JsonNode digest = json(send(getAuth("/api/digest")));

        // Degrading to "AI unavailable" would throw away facts that were already
        // computed. The observations below are derived, not generated.
        assertThat(digest.get("observations")).isNotEmpty();
        assertThat(digest.get("focusNextWeek")).isNotEmpty();
        assertThat(digest.get("narrative").asText()).contains(lastMonday.toString());
    }

    @Test
    @Order(4)
    @DisplayName("Regenerating a week replaces its digest rather than adding a second")
    void regeneratingReplaces() throws Exception {
        String firstId = json(send(getAuth("/api/digest"))).get("id").asText();

        assertThat(send(postAuth("/api/digest/generate?weekStart=" + lastMonday, null))
                .statusCode()).isEqualTo(200);

        JsonNode history = json(send(getAuth("/api/digest/history")));
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("id").asText()).isEqualTo(firstId);
    }

    @Test
    @Order(5)
    @DisplayName("A week with nothing logged is a 400 that says so, not an empty digest")
    void emptyWeekRefused() throws Exception {
        HttpResponse<String> response = send(postAuth(
                "/api/digest/generate?weekStart=" + lastMonday.minusWeeks(30), null));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("nothing to summarise");
    }

    @Test
    @Order(6)
    @DisplayName("A digest belongs to the caller and to nobody else")
    void digestIsScopedToTheCaller() throws Exception {
        RegisterRequest reg = new RegisterRequest();
        reg.setEmail("digeststranger@test.com");
        reg.setPassword("testPass123");
        reg.setFirstName("Someone");
        reg.setLastName("Else");
        assertThat(send(post("/api/auth/register", reg)).statusCode()).isEqualTo(200);

        LoginRequest login = new LoginRequest();
        login.setEmail("digeststranger@test.com");
        login.setPassword("testPass123");
        String otherJwt = json(send(post("/api/auth/login", login))).get("accessToken").asText();

        // There is no userId on these routes to tamper with; a new account has none.
        HttpResponse<String> theirs = send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/digest")))
                .header("Authorization", "Bearer " + otherJwt)
                .GET().build());
        assertThat(theirs.statusCode()).isEqualTo(204);
    }

    @Test
    @Order(7)
    @DisplayName("Digest routes require a token at all")
    void requiresAuthentication() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/digest"))).GET().build());

        // 401, not 403 — the frontend only attempts a silent refresh on 401 (AGENT.md §4).
        assertThat(response.statusCode()).isEqualTo(401);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ActivityRequest lifting(LocalDate day, int minutes,
                                    String movement, int sets, int reps, double weight) {
        ActivityRequest request = new ActivityRequest();
        request.setType(ActivityType.WEIGHT_TRAINING);
        request.setDuration(minutes);
        request.setStartTime(day.atTime(18, 0));
        request.setExercises(List.of(new ExerciseRequest(movement, sets, reps, weight)));
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
                .POST(body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
    }

    private HttpRequest getAuth(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url(path)))
                .header("Authorization", "Bearer " + jwt)
                .GET().build();
    }
}
