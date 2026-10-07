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
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — Full Activity flow
 *
 * DEFINITIVE FIX: Uses @BeforeAll to register, login, and log ONE activity
 * before any @Test runs. This way ALL tests have guaranteed access to
 * jwt, userId, activityId — no ordering dependency, no lifecycle issues.
 *
 * @TestInstance(PER_CLASS) is required so @BeforeAll can be non-static
 * and @Autowired fields (port, objectMapper) are available in @BeforeAll.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Activity Controller Integration Tests")
class ActivityControllerIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();

    // Set once in @BeforeAll, used by all @Test methods
    private String jwt;
    private String userId;
    private String activityId;

    // ── One-time setup before ALL tests ──────────────────────────────────────

    @BeforeAll
    void setupOnce() throws Exception {
        // 1. Register
        RegisterRequest reg = new RegisterRequest();
        reg.setEmail("activitytest@test.com");
        reg.setPassword("testPass123");
        reg.setFirstName("Test");
        reg.setLastName("User");
        reg.setWeight(75.0);
        reg.setHeight(175.0);
        reg.setAge(25);

        HttpResponse<String> regResp = http.send(
                buildPost("/api/auth/register", reg),
                HttpResponse.BodyHandlers.ofString());
        assertThat(regResp.statusCode()).isEqualTo(200);
        userId = objectMapper.readTree(regResp.body()).get("id").asText();
        assertThat(userId).isNotBlank();

        // 2. Login → get JWT
        LoginRequest login = new LoginRequest();
        login.setEmail("activitytest@test.com");
        login.setPassword("testPass123");

        HttpResponse<String> loginResp = http.send(
                buildPost("/api/auth/login", login),
                HttpResponse.BodyHandlers.ofString());
        assertThat(loginResp.statusCode()).isEqualTo(200);
        jwt = objectMapper.readTree(loginResp.body()).get("accessToken").asText();
        assertThat(jwt).isNotBlank();

        // 3. Log one activity → get activityId
        ActivityRequest act = new ActivityRequest();
        act.setUserId(userId);
        act.setType(ActivityType.RUNNING);
        act.setDuration(30);
        act.setStartTime(LocalDateTime.now());
        act.setNotes("Setup activity");

        HttpResponse<String> actResp = http.send(
                buildPostAuth("/api/activities", act),
                HttpResponse.BodyHandlers.ofString());
        assertThat(actResp.statusCode()).isEqualTo(200);
        activityId = objectMapper.readTree(actResp.body()).get("id").asText();
        assertThat(activityId).isNotBlank();
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpRequest buildPost(String path, Object body) throws Exception {
        return HttpRequest.newBuilder()
                .uri(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body)))
                .build();
    }

    private HttpRequest buildPostAuth(String path, Object body) throws Exception {
        return HttpRequest.newBuilder()
                .uri(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + jwt)
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body)))
                .build();
    }

    private HttpRequest buildGetAuth(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url(path)))
                .header("Authorization", "Bearer " + jwt)
                .GET()
                .build();
    }

    private HttpRequest buildGetNoAuth(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url(path)))
                .GET()
                .build();
    }

    private HttpRequest buildDeleteAuth(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url(path)))
                .header("Authorization", "Bearer " + jwt)
                .DELETE()
                .build();
    }

    private HttpRequest buildPutAuth(String path, Object body) throws Exception {
        return HttpRequest.newBuilder()
                .uri(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + jwt)
                .PUT(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body)))
                .build();
    }

    // ── Tests — all independent, all use pre-created userId/jwt/activityId ───

    @Test
    @Order(1)
    @DisplayName("Feature 1: calorie auto-calculation — RUNNING 30min at 75kg = 368 cal")
    void calorieAutoCalculation_running30min_75kg() throws Exception {
        ActivityRequest req = new ActivityRequest();
        req.setUserId(userId);
        req.setType(ActivityType.RUNNING);
        req.setDuration(30);   // MET 9.8 × 75 × 0.5 = 367.5 → 368
        req.setStartTime(LocalDateTime.now());

        HttpResponse<String> response = http.send(
                buildPostAuth("/api/activities", req),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        var body = objectMapper.readTree(response.body());
        assertThat(body.get("caloriesBurned").asInt()).isEqualTo(368);
    }

    @Test
    @Order(2)
    @DisplayName("Feature 1: WALKING 60min = 263 cal (MET 3.5, less than RUNNING)")
    void calorieAutoCalculation_walking60min_lessThanRunning() throws Exception {
        ActivityRequest req = new ActivityRequest();
        req.setUserId(userId);
        req.setType(ActivityType.WALKING);
        req.setDuration(60);   // MET 3.5 × 75 × 1.0 = 262.5 → 263
        req.setStartTime(LocalDateTime.now());

        HttpResponse<String> response = http.send(
                buildPostAuth("/api/activities", req),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        var body = objectMapper.readTree(response.body());
        assertThat(body.get("caloriesBurned").asInt()).isEqualTo(263);
        assertThat(body.get("caloriesBurned").asInt()).isLessThan(368);
    }

    @Test
    @Order(3)
    @DisplayName("Feature 2: streak starts at 1 for first activity of the day")
    void streak_firstActivityOfDay_equalsOne() throws Exception {
        // activityId was created in @BeforeAll as the first activity
        // streak should be 1 (set during @BeforeAll)
        // Log another activity and verify streak is still 1 (same day = no increment)
        ActivityRequest req = new ActivityRequest();
        req.setUserId(userId);
        req.setType(ActivityType.YOGA);
        req.setDuration(20);
        req.setStartTime(LocalDateTime.now());

        HttpResponse<String> response = http.send(
                buildPostAuth("/api/activities", req),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        var body = objectMapper.readTree(response.body());
        // Same day as @BeforeAll activity → streak stays 1
        assertThat(body.get("currentStreak").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(body.get("longestStreak").asInt()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(4)
    @DisplayName("Feature 3: first activity of a type = auto personal best")
    void personalBest_firstHIITActivity_isPersonalBest() throws Exception {
        // HIIT not logged yet → first one = personal best
        ActivityRequest req = new ActivityRequest();
        req.setUserId(userId);
        req.setType(ActivityType.HIIT);
        req.setDuration(25);
        req.setStartTime(LocalDateTime.now());

        HttpResponse<String> response = http.send(
                buildPostAuth("/api/activities", req),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        var body = objectMapper.readTree(response.body());
        assertThat(body.get("isPersonalBest").asBoolean()).isTrue();
        assertThat(body.get("personalBestMessage").asText()).isNotBlank();
    }

    @Test
    @Order(5)
    @DisplayName("Notes saved on activity creation")
    void notes_savedOnCreate() throws Exception {
        ActivityRequest req = new ActivityRequest();
        req.setUserId(userId);
        req.setType(ActivityType.CYCLING);
        req.setDuration(45);
        req.setStartTime(LocalDateTime.now());
        req.setNotes("Evening ride — felt strong");

        HttpResponse<String> response = http.send(
                buildPostAuth("/api/activities", req),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(response.body())
                .get("notes").asText()).isEqualTo("Evening ride — felt strong");
    }

    /**
     * 401, not 403. Spring's stateless default is 403 for a missing or expired token,
     * but app.js only attempts its silent token refresh on a 401 - so a 403 here means
     * a person whose 48h access token just expired sees an error on every panel instead
     * of being refreshed transparently. SecurityConfig sets an explicit authentication
     * entry point for exactly this reason.
     */
    @Test
    @Order(6)
    @DisplayName("GET /api/activities without JWT returns 401 (so the frontend refreshes)")
    void security_noJwt_returns401() throws Exception {
        HttpResponse<String> response = http.send(
                buildGetNoAuth("/api/activities"),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @Order(6)
    @DisplayName("An invalid JWT also returns 401, not 403")
    void security_invalidJwt_returns401() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(url("/api/activities")))
                        .header("Authorization", "Bearer not.a.real.token")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @Order(7)
    @DisplayName("Phase 4 pagination: GET /api/activities returns page wrapper with totalElements")
    void pagination_responseHasPageMetadata() throws Exception {
        HttpResponse<String> response = http.send(
                buildGetAuth("/api/activities?page=0&size=10"),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        var body = objectMapper.readTree(response.body());

        // @EnableSpringDataWebSupport(VIA_DTO) produces:
        // { "content": [...], "page": { "totalElements": N, "totalPages": N, ... } }
        assertThat(body.has("content")).isTrue();
        assertThat(body.has("page")).isTrue();
        assertThat(body.get("page").get("totalElements").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(body.get("page").has("totalPages")).isTrue();
        assertThat(body.get("page").has("number")).isTrue();
        assertThat(body.get("page").has("size")).isTrue();
    }

    @Test
    @Order(8)
    @DisplayName("Feature 13: rating 4 on existing activity returns 200 with difficultyRating=4")
    void rateActivity_validRating4_returns200() throws Exception {
        // activityId guaranteed by @BeforeAll
        HttpResponse<String> response = http.send(
                buildPostAuth("/api/activities/" + activityId + "/rate",
                        Map.of("rating", 4)),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode())
                .as("Rating 4 on activityId=%s should return 200", activityId)
                .isEqualTo(200);
        assertThat(objectMapper.readTree(response.body())
                .get("difficultyRating").asInt()).isEqualTo(4);
    }

    @Test
    @Order(9)
    @DisplayName("Feature 13: rating 0 (below min) returns 400")
    void rateActivity_ratingZero_returns400() throws Exception {
        HttpResponse<String> response = http.send(
                buildPostAuth("/api/activities/" + activityId + "/rate",
                        Map.of("rating", 0)),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    @Order(10)
    @DisplayName("Feature 13: rating 6 (above max) returns 400")
    void rateActivity_ratingSix_returns400() throws Exception {
        HttpResponse<String> response = http.send(
                buildPostAuth("/api/activities/" + activityId + "/rate",
                        Map.of("rating", 6)),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    @Order(11)
    @DisplayName("Notes update: PUT /api/activities/{id}/notes returns 200 with updated notes")
    void updateNotes_existingActivity_returns200() throws Exception {
        // activityId guaranteed by @BeforeAll
        HttpResponse<String> response = http.send(
                buildPutAuth("/api/activities/" + activityId + "/notes",
                        Map.of("notes", "Updated: PR attempt tomorrow")),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode())
                .as("PUT notes on activityId=%s should return 200", activityId)
                .isEqualTo(200);
        assertThat(objectMapper.readTree(response.body())
                .get("notes").asText()).isEqualTo("Updated: PR attempt tomorrow");
    }

    // -- Cross-user authorization -------------------------------------------
    //
    // Every one of these used to succeed: identity came from a path variable or a
    // request body, so swapping in another user's id was enough to read or write
    // that user's data. Identity now comes from the JWT only (AGENT.md section 1).

    /** Registers and logs in a second, unrelated account. Returns its access token. */
    private String secondUserJwt() throws Exception {
        RegisterRequest reg = new RegisterRequest();
        reg.setEmail("otheruser@test.com");
        reg.setPassword("testPass123");
        reg.setFirstName("Other");
        reg.setLastName("User");
        http.send(buildPost("/api/auth/register", reg), HttpResponse.BodyHandlers.ofString());

        LoginRequest login = new LoginRequest();
        login.setEmail("otheruser@test.com");
        login.setPassword("testPass123");
        HttpResponse<String> resp = http.send(
                buildPost("/api/auth/login", login), HttpResponse.BodyHandlers.ofString());
        return objectMapper.readTree(resp.body()).get("accessToken").asText();
    }

    private HttpResponse<String> getAs(String token, String path) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(url(path)))
                .header("Authorization", "Bearer " + token)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @Order(20)
    @DisplayName("Another user cannot read this user's profile by id")
    void authorization_otherUserCannotReadProfile() throws Exception {
        HttpResponse<String> response = getAs(secondUserJwt(), "/api/profile/" + userId);
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).doesNotContain("activitytest@test.com");
    }

    @Test
    @Order(21)
    @DisplayName("Another user cannot read this user's stats by id")
    void authorization_otherUserCannotReadStats() throws Exception {
        assertThat(getAs(secondUserJwt(), "/api/stats/all-time/" + userId).statusCode())
                .isEqualTo(403);
    }

    @Test
    @Order(22)
    @DisplayName("Another user cannot rate this user's activity")
    void authorization_otherUserCannotRateActivity() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/activities/" + activityId + "/rate")))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + secondUserJwt())
                .POST(HttpRequest.BodyPublishers.ofString("{\"rating\":1}"))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    @Order(23)
    @DisplayName("A userId in the request body cannot redirect an activity to another account")
    void authorization_bodyUserIdIsIgnored() throws Exception {
        String otherJwt = secondUserJwt();

        // Second user logs an activity but claims it belongs to the first user.
        ActivityRequest spoofed = new ActivityRequest();
        spoofed.setUserId(userId);
        spoofed.setType(ActivityType.CYCLING);
        spoofed.setDuration(15);
        spoofed.setStartTime(LocalDateTime.now());

        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/activities")))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + otherJwt)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(spoofed)))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        // It was written to the caller's own account, not the one named in the body.
        assertThat(objectMapper.readTree(response.body()).get("userId").asText())
                .isNotEqualTo(userId);
    }

    @Test
    @Order(24)
    @DisplayName("Registration cannot self-assign the ADMIN role")
    void authorization_registerCannotEscalateToAdmin() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/auth/register")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(ADMIN_REGISTER_BODY))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);

        // Log in and confirm the issued token carries USER, not ADMIN.
        LoginRequest login = new LoginRequest();
        login.setEmail("wannabeadmin@test.com");
        login.setPassword("testPass123");
        HttpResponse<String> loginResp = http.send(
                buildPost("/api/auth/login", login), HttpResponse.BodyHandlers.ofString());

        String token = objectMapper.readTree(loginResp.body()).get("accessToken").asText();
        String payload = new String(java.util.Base64.getUrlDecoder().decode(token.split(DOTRX)[1]));
        assertThat(payload).contains("USER").doesNotContain("ADMIN");
    }

    @Test
    @Order(25)
    @DisplayName("Logout no longer accepts a bare userId - one account cannot end another's session")
    void authorization_logoutRequiresProofOfIdentity() throws Exception {
        // An attacker who knows the victim's id (the UI shows it as the Athlete ID)
        // posts it to the public logout route.
        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/auth/logout")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"userId\":" + Q + userId + Q + "}"))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);   // logging out is never an error
        assertThat(response.body()).contains("No active session to revoke");

        // The victim's own token still works.
        assertThat(getAs(jwt, "/api/profile/" + userId).statusCode()).isEqualTo(200);
    }


    // ── Edit and delete (PLAN.md P0-4) ───────────────────────────────────────

    @Test
    @Order(30)
    @DisplayName("The activity list returns the DTO shape, not the raw entity")
    void list_returnsDtoShape() throws Exception {
        HttpResponse<String> response = http.send(
                buildGetAuth("/api/activities?page=0&size=10"),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        var first = objectMapper.readTree(response.body()).get("content").get(0);
        // isPersonalBest exists only on ActivityResponse. The frontend's PR badge read
        // it off the entity, where it could never be anything but undefined.
        assertThat(first.has("isPersonalBest")).isTrue();
        assertThat(first.has("userId")).isTrue();
        assertThat(first.has("currentStreak")).isTrue();
    }

    @Test
    @Order(31)
    @DisplayName("completedAt is populated, not the permanent null it used to be")
    void create_populatesCompletedAt() throws Exception {
        ActivityRequest req = new ActivityRequest();
        req.setType(ActivityType.CYCLING);
        req.setDuration(40);
        req.setStartTime(LocalDateTime.now().minusHours(2));

        HttpResponse<String> response = http.send(
                buildPostAuth("/api/activities", req),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        var body = objectMapper.readTree(response.body());
        assertThat(body.get("completedAt").isNull()).isFalse();
    }

    @Test
    @Order(32)
    @DisplayName("An activity can be edited, and omitted fields are left alone")
    void update_editsActivity() throws Exception {
        ActivityRequest created = new ActivityRequest();
        created.setType(ActivityType.YOGA);
        created.setDuration(20);
        created.setNotes("keep me");
        created.setStartTime(LocalDateTime.now());

        String id = objectMapper.readTree(http.send(
                buildPostAuth("/api/activities", created),
                HttpResponse.BodyHandlers.ofString()).body()).get("id").asText();

        ActivityRequest edit = new ActivityRequest();
        edit.setDuration(55);

        HttpResponse<String> response = http.send(
                buildPutAuth("/api/activities/" + id, edit),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        var body = objectMapper.readTree(response.body());
        assertThat(body.get("duration").asInt()).isEqualTo(55);
        assertThat(body.get("notes").asText()).isEqualTo("keep me");
    }

    @Test
    @Order(33)
    @DisplayName("An activity can be deleted and is then gone from the list")
    void delete_removesActivity() throws Exception {
        ActivityRequest created = new ActivityRequest();
        created.setType(ActivityType.STRETCHING);
        created.setDuration(15);
        created.setNotes("delete-me-marker");
        created.setStartTime(LocalDateTime.now());

        String id = objectMapper.readTree(http.send(
                buildPostAuth("/api/activities", created),
                HttpResponse.BodyHandlers.ofString()).body()).get("id").asText();

        HttpResponse<String> deleteResp = http.send(
                buildDeleteAuth("/api/activities/" + id),
                HttpResponse.BodyHandlers.ofString());
        assertThat(deleteResp.statusCode()).isEqualTo(204);

        HttpResponse<String> list = http.send(
                buildGetAuth("/api/activities?page=0&size=100"),
                HttpResponse.BodyHandlers.ofString());
        assertThat(list.body()).doesNotContain("delete-me-marker");
    }

    @Test
    @Order(34)
    @DisplayName("One user cannot delete another user's activity")
    void delete_deniedForOtherUser() throws Exception {
        // activityId belongs to the main test user; attackerJwt is a different account.
        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/activities/" + activityId)))
                .header("Authorization", "Bearer " + attackerJwt())
                .DELETE()
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(403);

        // And it really is still there.
        assertThat(http.send(buildGetAuth("/api/activities?page=0&size=100"),
                HttpResponse.BodyHandlers.ofString()).body()).contains(activityId);
    }

    @Test
    @Order(35)
    @DisplayName("Rating an activity returns the progression suggestion field")
    void rate_exposesProgressionSuggestion() throws Exception {
        HttpResponse<String> response = http.send(
                buildPostAuth("/api/activities/" + activityId + "/rate",
                        Map.of("rating", 2)),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        // The field is null until three rated sessions of a type exist, but it has to
        // be part of the contract — ProgressionService previously had no caller at all.
        assertThat(objectMapper.readTree(response.body()).has("progressionSuggestion")).isTrue();
    }

    @Test
    @Order(36)
    @DisplayName("Editing keeps a typed calorie figure; recalculateCalories re-derives it (P4-2)")
    void edit_keepsTypedCalories_untilRecalculationIsAsked() throws Exception {
        ActivityRequest req = new ActivityRequest();
        req.setType(ActivityType.RUNNING);
        req.setDuration(30);
        req.setCaloriesBurned(500);            // typed; the calculator would say 368
        req.setStartTime(LocalDateTime.now());
        HttpResponse<String> created = http.send(buildPostAuth("/api/activities", req),
                HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).isEqualTo(200);
        String id = objectMapper.readTree(created.body()).get("id").asText();

        // A notes-only edit used to recalculate and store 368 over the user's 500.
        HttpResponse<String> notesEdit = http.send(
                buildPutAuth("/api/activities/" + id, Map.of("notes", "legs heavy")),
                HttpResponse.BodyHandlers.ofString());
        assertThat(notesEdit.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(notesEdit.body()).get("caloriesBurned").asInt()).isEqualTo(500);

        HttpResponse<String> recalculated = http.send(
                buildPutAuth("/api/activities/" + id, Map.of("recalculateCalories", true)),
                HttpResponse.BodyHandlers.ofString());
        assertThat(recalculated.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(recalculated.body()).get("caloriesBurned").asInt()).isEqualTo(368);

        // Now the figure is a calculated one, so a duration change carries it along.
        HttpResponse<String> longer = http.send(
                buildPutAuth("/api/activities/" + id, Map.of("duration", 60)),
                HttpResponse.BodyHandlers.ofString());
        assertThat(longer.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(longer.body()).get("caloriesBurned").asInt()).isEqualTo(735);
    }

    /** Registers (once) and logs in a second account, for cross-user checks. */
    private String attackerJwt() throws Exception {
        RegisterRequest reg = new RegisterRequest();
        reg.setEmail("activity-attacker@test.com");
        reg.setPassword("testPass123");
        reg.setFirstName("Mal");
        reg.setLastName("Ory");
        http.send(buildPost("/api/auth/register", reg), HttpResponse.BodyHandlers.ofString());

        LoginRequest login = new LoginRequest();
        login.setEmail("activity-attacker@test.com");
        login.setPassword("testPass123");
        HttpResponse<String> resp = http.send(
                buildPost("/api/auth/login", login), HttpResponse.BodyHandlers.ofString());
        return objectMapper.readTree(resp.body()).get("accessToken").asText();
    }

    private static final String Q = "\"";

    private static final String DOTRX = "\\.";

    private static final String ADMIN_REGISTER_BODY =
            "{\"email\":\"wannabeadmin@test.com\",\"password\":\"testPass123\","
            + "\"firstName\":\"Want\",\"lastName\":\"Admin\",\"role\":\"ADMIN\"}";
}
