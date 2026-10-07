package com.project.fitness.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.fitness.dto.ActivityRequest;
import com.project.fitness.dto.LoginRequest;
import com.project.fitness.dto.MealRequest;
import com.project.fitness.dto.RegisterRequest;
import com.project.fitness.model.ActivityType;
import com.project.fitness.model.GoalType;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nutrition end to end (PLAN P2-1).
 *
 * <p>Plain {@code java.net.http.HttpClient}, not TestRestTemplate or MockMvc — both
 * moved artifact in Spring Boot 4.1 and broke this build twice (AGENT.md §7).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Nutrition Integration Tests")
class NutritionIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();

    private String jwt;
    private String userId;
    private String breakfastId;

    /** 70kg / 175cm / 30y → sex-neutral Mifflin-St Jeor 1566 BMR, ×1.2 = 1879. */
    private static final int EXPECTED_MAINTENANCE = 1879;

    @BeforeAll
    void setupOnce() throws Exception {
        RegisterRequest reg = new RegisterRequest();
        reg.setEmail("nutritiontest@test.com");
        reg.setPassword("testPass123");
        reg.setFirstName("Meal");
        reg.setLastName("Tester");
        reg.setWeight(70.0);
        reg.setHeight(175.0);
        reg.setAge(30);
        HttpResponse<String> regResp = send(post("/api/auth/register", reg));
        assertThat(regResp.statusCode()).isEqualTo(200);
        userId = json(regResp).get("id").asText();

        LoginRequest login = new LoginRequest();
        login.setEmail("nutritiontest@test.com");
        login.setPassword("testPass123");
        jwt = json(send(post("/api/auth/login", login))).get("accessToken").asText();
        assertThat(jwt).isNotBlank();
    }

    // ── Logging ──────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("A meal round-trips its calories and macros, and is not marked as estimated")
    void logsMeal() throws Exception {
        HttpResponse<String> response = send(postAuth("/api/nutrition/meals",
                meal(MealType.BREAKFAST, "Porridge and berries", 420, 12.0, 60.0, 9.0)));
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode body = json(response);
        breakfastId = body.get("id").asText();
        assertThat(body.get("calories").asInt()).isEqualTo(420);
        assertThat(body.get("proteinGrams").asDouble()).isEqualTo(12.0);
        // Typed by the user, so it carries no estimate badge.
        assertThat(body.get("aiEstimated").asBoolean()).isFalse();
    }

    @Test
    @Order(2)
    @DisplayName("Calories are derived from the macros when none were given")
    void derivesCaloriesFromMacros() throws Exception {
        HttpResponse<String> response = send(postAuth("/api/nutrition/meals",
                meal(MealType.LUNCH, "Chicken and rice", null, 45.0, 70.0, 12.0)));
        assertThat(response.statusCode()).isEqualTo(200);

        // 45*4 + 70*4 + 12*9 = 568
        assertThat(json(response).get("calories").asInt()).isEqualTo(568);
    }

    @Test
    @Order(3)
    @DisplayName("A meal with neither calories nor macros is a 400, not a saved empty row")
    void emptyMealRejected() throws Exception {
        HttpResponse<String> response = send(postAuth("/api/nutrition/meals",
                meal(MealType.SNACK, "something", null, null, null, null)));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("at least a calorie figure");
    }

    // ── The summary: both sides of the equation ──────────────────────────────

    @Test
    @Order(4)
    @DisplayName("The daily summary subtracts maintenance as well as training burn")
    void summarySubtractsMaintenance() throws Exception {
        // A workout on the same day, so both sides of the equation are populated.
        ActivityRequest workout = new ActivityRequest();
        workout.setType(ActivityType.RUNNING);
        workout.setDuration(30);
        workout.setCaloriesBurned(300);
        workout.setStartTime(LocalDateTime.now());
        assertThat(send(postAuth("/api/activities", workout)).statusCode()).isEqualTo(200);

        JsonNode summary = json(send(getAuth("/api/nutrition/summary")));

        int eaten = 420 + 568;
        assertThat(summary.get("caloriesIn").asInt()).isEqualTo(eaten);
        assertThat(summary.get("caloriesBurnedExercise").asInt()).isEqualTo(300);
        assertThat(summary.get("maintenanceCalories").asInt()).isEqualTo(EXPECTED_MAINTENANCE);

        // The assertion the whole feature turns on. "Eaten minus training" would be
        // +688 — every honest day of eating reading as a surplus. Subtracting the
        // ~1900 kcal a body spends existing is what makes the number mean anything.
        assertThat(summary.get("netCalories").asInt())
                .isEqualTo(eaten - EXPECTED_MAINTENANCE - 300);
        assertThat(summary.get("balance").asText()).isEqualTo("DEFICIT");

        assertThat(summary.get("byMealType").get("BREAKFAST").asInt()).isEqualTo(420);
        assertThat(summary.get("byMealType").get("LUNCH").asInt()).isEqualTo(568);
    }

    @Test
    @Order(5)
    @DisplayName("The series leaves an unlogged day blank rather than drawing a fabricated deficit")
    void seriesLeavesUnloggedDaysNull() throws Exception {
        JsonNode series = json(send(getAuth("/api/nutrition/daily?days=7")));
        assertThat(series).hasSize(7);

        // Today has meals; the six days before it do not. Zero intake means "didn't
        // log", not "fasted", and plotting it as a 1,900 kcal deficit would invent the
        // most dramatic day on the chart out of missing data.
        assertThat(series.get(6).get("netCalories").isNull()).isFalse();
        for (int i = 0; i < 6; i++) {
            assertThat(series.get(i).get("caloriesIn").asInt()).isZero();
            assertThat(series.get(i).get("netCalories").isNull()).isTrue();
        }
    }

    // ── Goals driven by the meal log ─────────────────────────────────────────

    @Test
    @Order(6)
    @DisplayName("A daily calorie budget counts what was eaten and warns when it is blown")
    void calorieBudgetTracksMeals() throws Exception {
        HttpResponse<String> created = send(postAuth("/api/goals", Map.of(
                "type", GoalType.CALORIE_INTAKE_PER_DAY.name(),
                "targetValue", 1200)));
        assertThat(created.statusCode()).isEqualTo(200);

        // 988 already eaten today, so the budget is part-used but not yet blown.
        JsonNode budget = goalOfType(GoalType.CALORIE_INTAKE_PER_DAY);
        assertThat(budget.get("currentValue").asInt()).isEqualTo(988);
        assertThat(budget.get("remaining").asInt()).isEqualTo(212);
        assertThat(budget.get("isExceeded").asBoolean()).isFalse();
        // A budget is never an achievement, however little was eaten.
        assertThat(budget.get("isCompleted").asBoolean()).isFalse();
        assertThat(budget.get("period").asText()).isEqualTo("DAILY");
        assertThat(budget.get("unit").asText()).isEqualTo("kcal");

        // One more meal takes it over, and the response says so at the moment it happens.
        HttpResponse<String> over = send(postAuth("/api/nutrition/meals",
                meal(MealType.DINNER, "Pizza", 900, null, null, null)));
        JsonNode messages = json(over).get("goalAchievements");
        assertThat(messages).isNotNull();
        assertThat(messages.get(0).asText()).contains("budget").contains("688");

        JsonNode blown = goalOfType(GoalType.CALORIE_INTAKE_PER_DAY);
        assertThat(blown.get("isExceeded").asBoolean()).isTrue();
        assertThat(blown.get("exceededBy").asInt()).isEqualTo(688);
        assertThat(blown.get("remaining").asInt()).isZero();
    }

    @Test
    @Order(7)
    @DisplayName("Deleting a meal gives the budget back — it is recomputed, not decremented")
    void deletingMealGivesBudgetBack() throws Exception {
        HttpResponse<String> deleted = send(deleteAuth("/api/nutrition/meals/" + breakfastId));
        assertThat(deleted.statusCode()).isEqualTo(204);

        // 1888 - 420. A running total that was only ever added to could not have come
        // back down, which is why nutrition goals are rebuilt from the log.
        JsonNode budget = goalOfType(GoalType.CALORIE_INTAKE_PER_DAY);
        assertThat(budget.get("currentValue").asInt()).isEqualTo(1468);
        assertThat(budget.get("exceededBy").asInt()).isEqualTo(268);
    }

    @Test
    @Order(8)
    @DisplayName("A body-weight goal is scored from the scale and measured from where it started")
    void bodyWeightGoalTracksTheScale() throws Exception {
        HttpResponse<String> created = send(postAuth("/api/goals", Map.of(
                "type", GoalType.TARGET_WEIGHT.name(),
                "targetValue", 66.0)));
        assertThat(created.statusCode()).isEqualTo(200);

        // Created at the profile weight of 70kg, heading for 66.
        JsonNode fresh = goalOfType(GoalType.TARGET_WEIGHT);
        assertThat(fresh.get("startValue").asDouble()).isEqualTo(70.0);
        assertThat(fresh.get("progressPercent").asDouble()).isZero();
        assertThat(fresh.get("period").asText()).isEqualTo("NONE");

        // Weigh in at 68: half of the 4kg between 70 and 66.
        assertThat(send(putAuth("/api/health/metrics/" + userId,
                Map.of("weight", 68.0, "height", 175.0))).statusCode()).isEqualTo(200);

        JsonNode halfway = goalOfType(GoalType.TARGET_WEIGHT);
        assertThat(halfway.get("currentValue").asDouble()).isEqualTo(68.0);
        // As a plain current/target ratio this would be 103% and meaningless.
        assertThat(halfway.get("progressPercent").asDouble()).isEqualTo(50.0);
        assertThat(halfway.get("isCompleted").asBoolean()).isFalse();

        // Reaching it completes it.
        assertThat(send(putAuth("/api/health/metrics/" + userId,
                Map.of("weight", 65.8, "height", 175.0))).statusCode()).isEqualTo(200);

        JsonNode done = goalOfType(GoalType.TARGET_WEIGHT);
        assertThat(done.get("isCompleted").asBoolean()).isTrue();
        assertThat(done.get("progressPercent").asDouble()).isEqualTo(100.0);
    }

    @Test
    @Order(9)
    @DisplayName("A workout does not move a calorie budget, however many calories it burned")
    void workoutDoesNotFeedTheBudget() throws Exception {
        int before = goalOfType(GoalType.CALORIE_INTAKE_PER_DAY).get("currentValue").asInt();

        ActivityRequest workout = new ActivityRequest();
        workout.setType(ActivityType.CYCLING);
        workout.setDuration(60);
        workout.setCaloriesBurned(500);
        workout.setStartTime(LocalDateTime.now());
        assertThat(send(postAuth("/api/activities", workout)).statusCode()).isEqualTo(200);

        // Burn and intake are the same unit and the opposite meaning — the obvious bug
        // to write here would have been adding one to the other.
        assertThat(goalOfType(GoalType.CALORIE_INTAKE_PER_DAY).get("currentValue").asInt())
                .isEqualTo(before);
    }

    // ── Cross-user authorization ─────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("Another user cannot read, edit or delete this user's food log")
    void crossUserAccessDenied() throws Exception {
        RegisterRequest reg = new RegisterRequest();
        reg.setEmail("nutritionintruder@test.com");
        reg.setPassword("testPass123");
        reg.setFirstName("Nosy");
        reg.setLastName("Neighbour");
        assertThat(send(post("/api/auth/register", reg)).statusCode()).isEqualTo(200);

        LoginRequest login = new LoginRequest();
        login.setEmail("nutritionintruder@test.com");
        login.setPassword("testPass123");
        String intruderJwt = json(send(post("/api/auth/login", login))).get("accessToken").asText();

        // Log a meal as the owner to have a concrete id to attack.
        String mealId = json(send(postAuth("/api/nutrition/meals",
                meal(MealType.SNACK, "Apple", 80, null, null, null)))).get("id").asText();

        HttpResponse<String> edit = send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/nutrition/meals/" + mealId)))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + intruderJwt)
                .PUT(HttpRequest.BodyPublishers.ofString("{\"calories\":9999}"))
                .build());
        assertThat(edit.statusCode()).isEqualTo(403);

        HttpResponse<String> delete = send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/nutrition/meals/" + mealId)))
                .header("Authorization", "Bearer " + intruderJwt)
                .DELETE().build());
        assertThat(delete.statusCode()).isEqualTo(403);

        // And the intruder's own summary sees none of it — there is no userId segment
        // on these routes to tamper with in the first place.
        JsonNode theirSummary = json(send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/nutrition/summary")))
                .header("Authorization", "Bearer " + intruderJwt)
                .GET().build()));
        assertThat(theirSummary.get("caloriesIn").asInt()).isZero();
    }

    @Test
    @Order(11)
    @DisplayName("Nutrition routes require a token at all")
    void requiresAuthentication() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/nutrition/summary")))
                .GET().build());

        // 401, not 403 — the frontend only attempts a silent refresh on 401 (AGENT.md §4).
        assertThat(response.statusCode()).isEqualTo(401);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private JsonNode goalOfType(GoalType type) throws Exception {
        JsonNode goals = json(send(getAuth("/api/goals/" + userId)));
        for (JsonNode goal : goals) {
            if (type.name().equals(goal.get("type").asText())) return goal;
        }
        throw new AssertionError("No goal of type " + type + " found in " + goals);
    }

    private MealRequest meal(MealType type, String description, Integer calories,
                             Double protein, Double carbs, Double fat) {
        MealRequest request = new MealRequest();
        request.setMealType(type);
        request.setDescription(description);
        request.setCalories(calories);
        request.setProteinGrams(protein);
        request.setCarbsGrams(carbs);
        request.setFatGrams(fat);
        request.setLoggedAt(LocalDate.now().atTime(12, 0));
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

    private HttpRequest putAuth(String path, Object body) throws Exception {
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

    private HttpRequest deleteAuth(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url(path)))
                .header("Authorization", "Bearer " + jwt)
                .DELETE().build();
    }
}
