package com.project.fitness.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.fitness.dto.LoginRequest;
import com.project.fitness.dto.RegisterRequest;
import com.project.fitness.model.Goal;
import com.project.fitness.model.GoalType;
import com.project.fitness.model.NotificationType;
import com.project.fitness.model.User;
import com.project.fitness.repository.GoalRepository;
import com.project.fitness.repository.UserRepository;
import com.project.fitness.service.NotificationService;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The notification feed end to end (PLAN P2-2).
 *
 * <p>Plain {@code java.net.http.HttpClient}, not TestRestTemplate or MockMvc (AGENT.md §7).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Notification Integration Tests")
class NotificationIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private NotificationService notificationService;
    @Autowired private UserRepository userRepository;
    @Autowired private GoalRepository goalRepository;

    private final HttpClient http = HttpClient.newHttpClient();

    private String jwt;
    private String userId;
    private String firstNotificationId;

    @BeforeAll
    void setupOnce() throws Exception {
        RegisterRequest reg = new RegisterRequest();
        reg.setEmail("notiftest@test.com");
        reg.setPassword("testPass123");
        reg.setFirstName("Notify");
        reg.setLastName("Tester");
        HttpResponse<String> regResp = send(post("/api/auth/register", reg));
        assertThat(regResp.statusCode()).isEqualTo(200);
        userId = json(regResp).get("id").asText();

        LoginRequest login = new LoginRequest();
        login.setEmail("notiftest@test.com");
        login.setPassword("testPass123");
        jwt = json(send(post("/api/auth/login", login))).get("accessToken").asText();
    }

    @Test
    @Order(1)
    @DisplayName("A new account's feed is empty and its badge is zero")
    void startsEmpty() throws Exception {
        JsonNode feed = json(send(getAuth("/api/notifications")));
        assertThat(feed.get("content")).isEmpty();

        assertThat(json(send(getAuth("/api/notifications/unread-count")))
                .get("unread").asInt()).isZero();
    }

    @Test
    @Order(2)
    @DisplayName("A produced notification reaches the feed and the unread count")
    void producedNotificationReachesTheFeed() throws Exception {
        User user = userRepository.findById(userId).orElseThrow();
        notificationService.notify(user, NotificationType.PLATEAU,
                "Your running has plateaued",
                "Try increasing intensity or duration.",
                "insights", "plateau:RUNNING:" + LocalDate.now());

        JsonNode feed = json(send(getAuth("/api/notifications")));
        assertThat(feed.get("content")).hasSize(1);

        JsonNode item = feed.get("content").get(0);
        firstNotificationId = item.get("id").asText();
        assertThat(item.get("type").asText()).isEqualTo("PLATEAU");
        assertThat(item.get("isRead").asBoolean()).isFalse();
        // A panel name, not a URL — the frontend owns the routing table.
        assertThat(item.get("targetPanel").asText()).isEqualTo("insights");

        assertThat(json(send(getAuth("/api/notifications/unread-count")))
                .get("unread").asInt()).isEqualTo(1);
    }

    @Test
    @Order(3)
    @DisplayName("The same dedupe key is not announced twice")
    void dedupeKeySuppressesRepeats() throws Exception {
        User user = userRepository.findById(userId).orElseThrow();
        notificationService.notify(user, NotificationType.PLATEAU,
                "Your running has plateaued (again)",
                "Same finding, same week.",
                "insights", "plateau:RUNNING:" + LocalDate.now());

        // A plateau is still a plateau next time the job runs. A feed that repeats
        // itself is one people stop reading.
        assertThat(json(send(getAuth("/api/notifications"))).get("content")).hasSize(1);
    }

    @Test
    @Order(4)
    @DisplayName("Marking one read clears it from the count but keeps it in the feed")
    void markReadClearsTheCount() throws Exception {
        HttpResponse<String> response = send(postAuth("/api/notifications/" + firstNotificationId + "/read"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).get("isRead").asBoolean()).isTrue();

        assertThat(json(send(getAuth("/api/notifications/unread-count")))
                .get("unread").asInt()).isZero();
        // Read is not deleted — the feed is still a record of what happened.
        assertThat(json(send(getAuth("/api/notifications"))).get("content")).hasSize(1);
    }

    @Test
    @Order(5)
    @DisplayName("The daily deadline job warns once per goal, not once per run")
    void deadlineJobWarnsOnce() throws Exception {
        User user = userRepository.findById(userId).orElseThrow();
        goalRepository.save(Goal.builder()
                .user(user).type(GoalType.WORKOUTS_PER_WEEK)
                .targetValue(5.0).currentValue(1.0).isCompleted(false)
                .deadline(LocalDate.now().plusDays(3))
                .periodStart(LocalDate.now())
                .build());

        notificationService.warnAboutApproachingDeadlines();
        int afterFirstRun = json(send(getAuth("/api/notifications"))).get("content").size();

        notificationService.warnAboutApproachingDeadlines();
        int afterSecondRun = json(send(getAuth("/api/notifications"))).get("content").size();

        assertThat(afterFirstRun).isEqualTo(2);      // the plateau above, plus this
        // The key includes the goal and its deadline, so running daily does not
        // re-announce the same commitment every morning until it passes.
        assertThat(afterSecondRun).isEqualTo(afterFirstRun);

        JsonNode newest = json(send(getAuth("/api/notifications"))).get("content").get(0);
        assertThat(newest.get("type").asText()).isEqualTo("GOAL_DEADLINE");
        assertThat(newest.get("title").asText()).isEqualTo("A goal is due in 3 days");
        assertThat(newest.get("targetPanel").asText()).isEqualTo("goals");
    }

    @Test
    @Order(6)
    @DisplayName("Mark-all-read clears everything in one call")
    void markAllRead() throws Exception {
        HttpResponse<String> response = send(postAuth("/api/notifications/read-all"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).get("marked").asInt()).isEqualTo(1);   // only the deadline one was unread

        assertThat(json(send(getAuth("/api/notifications/unread-count")))
                .get("unread").asInt()).isZero();
    }

    // ── Authorization ────────────────────────────────────────────────────────

    @Test
    @Order(7)
    @DisplayName("Another user cannot read — and so silence — this user's notifications")
    void crossUserAccessDenied() throws Exception {
        User user = userRepository.findById(userId).orElseThrow();
        var mine = notificationService.notify(user, NotificationType.GENERAL,
                "Private", "Mine alone", "overview", null);
        assertThat(mine).isNotNull();

        RegisterRequest reg = new RegisterRequest();
        reg.setEmail("notifintruder@test.com");
        reg.setPassword("testPass123");
        reg.setFirstName("Nosy");
        reg.setLastName("Neighbour");
        assertThat(send(post("/api/auth/register", reg)).statusCode()).isEqualTo(200);

        LoginRequest login = new LoginRequest();
        login.setEmail("notifintruder@test.com");
        login.setPassword("testPass123");
        String intruderJwt = json(send(post("/api/auth/login", login))).get("accessToken").asText();

        HttpResponse<String> attempt = send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/notifications/" + mine.getId() + "/read")))
                .header("Authorization", "Bearer " + intruderJwt)
                .POST(HttpRequest.BodyPublishers.noBody()).build());
        assertThat(attempt.statusCode()).isEqualTo(403);

        // And their own feed sees none of it.
        JsonNode theirFeed = json(send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/notifications")))
                .header("Authorization", "Bearer " + intruderJwt)
                .GET().build()));
        assertThat(theirFeed.get("content")).isEmpty();
    }

    @Test
    @Order(8)
    @DisplayName("Notification routes require a token at all")
    void requiresAuthentication() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/notifications"))).GET().build());

        // 401, not 403 — the frontend only attempts a silent refresh on 401 (AGENT.md §4).
        assertThat(response.statusCode()).isEqualTo(401);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

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

    private HttpRequest postAuth(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url(path)))
                .header("Authorization", "Bearer " + jwt)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
    }

    private HttpRequest getAuth(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url(path)))
                .header("Authorization", "Bearer " + jwt)
                .GET().build();
    }
}
