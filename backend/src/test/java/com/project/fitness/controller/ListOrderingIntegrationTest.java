package com.project.fitness.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.fitness.model.*;
import com.project.fitness.repository.ChallengeRepository;
import com.project.fitness.repository.UserRepository;
import com.project.fitness.repository.WorkoutPlanRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — lists come back in a defined order, and the long one is paged
 * (PLAN P4-19).
 *
 * <p>Three lists had no ORDER BY, so their order was whatever the database felt like:
 * a user's recommendations (also unbounded), a user's challenges, and a plan's days.
 * Days are keyed by random UUIDs, so "whatever the database felt like" is shuffled.
 *
 * <p>Per AGENT.md §7, plain java.net.http.HttpClient — not TestRestTemplate.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("List Ordering Integration Tests")
class ListOrderingIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private ChallengeRepository challengeRepository;
    @Autowired private WorkoutPlanRepository planRepository;

    private final HttpClient http = HttpClient.newHttpClient();

    private String jwt, userId;

    @BeforeAll
    void setUp() throws Exception {
        userId = json(send(request("POST", "/api/auth/register", Map.of(
                "email", "ordering@test.com", "password", "testPass123", "firstName", "Or", "lastName", "Der",
                "weight", 70.0, "height", 175.0, "age", 30), null))).get("id").asText();
        jwt = json(send(request("POST", "/api/auth/login",
                Map.of("email", "ordering@test.com", "password", "testPass123"), null))).get("accessToken").asText();
    }

    // ── Recommendations ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Recommendations are paged, newest first")
    void recommendationsArePagedNewestFirst() throws Exception {
        String activityId = json(send(post("/api/activities", Map.of(
                "type", "RUNNING", "duration", 30, "startTime", LocalDateTime.now().toString())))).get("id").asText();
        for (int i = 0; i < 3; i++) {
            // With no Gemini key each call saves the rule-based fallback, which is all this needs.
            assertThat(send(post("/api/recommendation/generate", Map.of("activityId", activityId))).statusCode())
                    .isEqualTo(200);
        }

        JsonNode first = json(send(get("/api/recommendation/user/" + userId + "?page=0&size=2")));
        JsonNode second = json(send(get("/api/recommendation/user/" + userId + "?page=1&size=2")));

        assertThat(first.get("content")).hasSize(2);
        assertThat(second.get("content")).hasSize(1);
        assertThat(first.get("page").get("totalElements").asInt()).isEqualTo(3);
        assertThat(first.get("page").get("totalPages").asInt()).isEqualTo(2);

        // Newest first, across the page boundary.
        List<String> created = new ArrayList<>();
        for (JsonNode r : first.get("content")) created.add(r.get("createdAt").asText());
        for (JsonNode r : second.get("content")) created.add(r.get("createdAt").asText());
        assertThat(created).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    @DisplayName("A negative page is page 0 and an oversized page is capped, as on the other paged lists")
    void pagingParametersAreForgiving() throws Exception {
        assertThat(send(get("/api/recommendation/user/" + userId + "?page=-4")).statusCode()).isEqualTo(200);
        JsonNode huge = json(send(get("/api/recommendation/user/" + userId + "?size=100000")));
        assertThat(huge.get("page").get("size").asInt()).isEqualTo(100);
    }

    // ── Challenges ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("A user's challenges come back newest first")
    void challengesAreNewestFirst() throws Exception {
        User me = userRepository.findById(userId).orElseThrow();
        for (String title : new String[] {"first", "second", "third"}) {
            Challenge c = Challenge.builder().creator(me).title(title).type(ChallengeType.WORKOUT_COUNT)
                    .status(ChallengeStatus.PENDING).startDate(LocalDate.now()).endDate(LocalDate.now().plusDays(7))
                    .build();
            c.addParticipant(ChallengeParticipant.builder().user(me).status(ParticipantStatus.ACCEPTED)
                    .isCreator(true).build());
            challengeRepository.saveAndFlush(c);
            Thread.sleep(25);   // createdAt is stamped on insert; keep the three apart
        }

        List<String> titles = new ArrayList<>();
        for (JsonNode c : json(send(get("/api/challenges/user/" + userId)))) titles.add(c.get("title").asText());

        assertThat(titles).containsExactly("third", "second", "first");
    }

    // ── Workout plan days ───────────────────────────────────────────────────

    @Test
    @DisplayName("A plan's days come back in day order, whatever order they were stored in")
    void planDaysAreInDayOrder() throws Exception {
        WorkoutPlan plan = WorkoutPlan.builder().name("Order test").totalDays(6).difficulty("BEGINNER")
                .goal("ENDURANCE").build();
        // Added backwards. The keys are random UUIDs, so without an ORDER BY the order
        // the database returns them in has nothing to do with this one.
        for (int day = 6; day >= 1; day--) {
            plan.getDays().add(WorkoutPlanDay.builder().plan(plan).dayNumber(day).isRestDay(day % 3 == 0)
                    .durationMinutes(20).instructions("day " + day).build());
        }
        String planId = planRepository.saveAndFlush(plan).getId();

        HttpResponse<String> response = send(get("/api/plans/" + planId));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);

        List<Integer> numbers = new ArrayList<>();
        for (JsonNode d : json(response).get("days")) numbers.add(d.get("dayNumber").asInt());

        assertThat(numbers).containsExactly(1, 2, 3, 4, 5, 6);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

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

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder().uri(URI.create(url(path)))
                .header("Authorization", "Bearer " + jwt).GET().build();
    }
}
