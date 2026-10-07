package com.project.fitness.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.fitness.dto.LoginRequest;
import com.project.fitness.dto.RegisterRequest;
import com.project.fitness.model.BodyMetricsLog;
import com.project.fitness.model.User;
import com.project.fitness.repository.BodyMetricsLogRepository;
import com.project.fitness.repository.UserRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pain, sleep and body-metrics history are paged (PLAN P3-3).
 *
 * <p>Each of these returned the user's entire record in one response, growing with
 * every entry forever. Plain {@code java.net.http.HttpClient}, not TestRestTemplate or
 * MockMvc (AGENT.md §7).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("History Pagination Integration Tests")
class HistoryPaginationIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private BodyMetricsLogRepository bodyMetricsLogRepository;

    private final HttpClient http = HttpClient.newHttpClient();

    private String jwt;
    private String userId;
    private String otherJwt;

    @BeforeAll
    void setupOnce() throws Exception {
        String[] mine = register("pagetest@test.com");
        userId = mine[0];
        jwt = mine[1];
        otherJwt = register("pageother@test.com")[1];
    }

    // ── Pain ─────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("Pain history comes back one page at a time, newest first")
    void painHistoryIsPaged() throws Exception {
        for (int i = 0; i < 25; i++) {
            assertThat(send(postAuth("/api/pain-log", Map.of(
                    "bodyPart", "LEFT_KNEE", "severity", 1 + i % 5, "description", "entry " + i)))
                    .statusCode()).isEqualTo(200);
        }

        JsonNode first = json(send(getAuth("/api/pain-log/" + userId + "?page=0&size=20", jwt)));
        assertThat(first.get("content")).hasSize(20);
        assertThat(first.get("page").get("totalElements").asInt()).isEqualTo(25);
        assertThat(first.get("page").get("totalPages").asInt()).isEqualTo(2);
        assertNewestFirst(first.get("content"), "loggedAt");

        JsonNode second = json(send(getAuth("/api/pain-log/" + userId + "?page=1&size=20", jwt)));
        assertThat(second.get("content")).hasSize(5);
    }

    @Test
    @Order(2)
    @DisplayName("Omitting page and size gets the first 20")
    void painHistoryDefaults() throws Exception {
        JsonNode page = json(send(getAuth("/api/pain-log/" + userId, jwt)));
        assertThat(page.get("content")).hasSize(20);
        assertThat(page.get("page").get("number").asInt()).isZero();
    }

    @Test
    @Order(3)
    @DisplayName("An oversized page is capped at 100 and a negative page is page 0")
    void pageBoundsAreClamped() throws Exception {
        JsonNode huge = json(send(getAuth("/api/pain-log/" + userId + "?size=100000", jwt)));
        assertThat(huge.get("page").get("size").asInt()).isEqualTo(100);

        HttpResponse<String> negative = send(getAuth("/api/pain-log/" + userId + "?page=-3", jwt));
        // Unclamped, PageRequest throws IllegalArgumentException on it — a 500.
        assertThat(negative.statusCode()).isEqualTo(200);
        assertThat(json(negative).get("page").get("number").asInt()).isZero();
    }

    @Test
    @Order(4)
    @DisplayName("Someone else's pain history is still refused")
    void painHistoryIsOwnerOnly() throws Exception {
        assertThat(send(getAuth("/api/pain-log/" + userId, otherJwt)).statusCode()).isEqualTo(403);
    }

    // ── Sleep ────────────────────────────────────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("Sleep history is paged, most recent night first")
    void sleepHistoryIsPaged() throws Exception {
        LocalDate today = LocalDate.now();
        for (int daysAgo : new int[]{5, 1, 3}) {
            assertThat(send(postAuth("/api/health/sleep/" + userId, Map.of(
                    "sleepDate", today.minusDays(daysAgo).toString(),
                    "hoursSlept", 7.0, "qualityRating", 4))).statusCode()).isEqualTo(200);
        }

        JsonNode page = json(send(getAuth("/api/health/sleep/" + userId + "/history?size=2", jwt)));
        assertThat(page.get("content")).hasSize(2);
        assertThat(page.get("page").get("totalElements").asInt()).isEqualTo(3);
        // Logged out of order on purpose: the order must come from sleepDate.
        assertThat(page.get("content").get(0).get("sleepDate").asText())
                .isEqualTo(today.minusDays(1).toString());
        assertThat(page.get("content").get(1).get("sleepDate").asText())
                .isEqualTo(today.minusDays(3).toString());

        assertThat(send(getAuth("/api/health/sleep/" + userId + "/history", otherJwt)).statusCode())
                .isEqualTo(403);
    }

    // ── Body metrics ─────────────────────────────────────────────────────────

    @Test
    @Order(6)
    @DisplayName("Weigh-in history has its own paged endpoint, newest first")
    void metricsHistoryIsPaged() throws Exception {
        for (double kg : new double[]{80.0, 79.0, 78.0}) {
            assertThat(send(putAuth("/api/health/metrics/" + userId, Map.of("weight", kg, "height", 178)))
                    .statusCode()).isEqualTo(200);
        }

        JsonNode page = json(send(getAuth("/api/health/metrics/" + userId + "/history?size=2", jwt)));
        assertThat(page.get("content")).hasSize(2);
        assertThat(page.get("page").get("totalElements").asInt()).isEqualTo(3);
        assertThat(page.get("content").get(0).get("weight").asDouble()).isEqualTo(78.0);

        // The chart still gets its trend from /metrics, oldest first, with a total.
        JsonNode metrics = json(send(getAuth("/api/health/metrics/" + userId, jwt)));
        assertThat(metrics.get("history")).hasSize(3);
        assertThat(metrics.get("history").get(0).get("weight").asDouble()).isEqualTo(80.0);
        assertThat(metrics.get("historyTotal").asInt()).isEqualTo(3);

        assertThat(send(getAuth("/api/health/metrics/" + userId + "/history", otherJwt)).statusCode())
                .isEqualTo(403);
    }

    @Test
    @Order(7)
    @DisplayName("/metrics embeds only the latest 100 weigh-ins, and says how many exist")
    void embeddedHistoryIsCapped() throws Exception {
        // 102 more, all newer than the three weigh-ins above (loggedAt is a
        // @CreationTimestamp, so these are stamped now). Weights 84.95–90, so none
        // collides with the earlier 78–80.
        User user = userRepository.findById(userId).orElseThrow();
        List<BodyMetricsLog> newer = new ArrayList<>();
        for (int i = 0; i < 102; i++) {
            newer.add(BodyMetricsLog.builder().user(user).weight(90.0 - i * 0.05).height(178.0).build());
        }
        bodyMetricsLogRepository.saveAll(newer);

        JsonNode metrics = json(send(getAuth("/api/health/metrics/" + userId, jwt)));
        JsonNode history = metrics.get("history");
        assertThat(history).hasSize(100);
        assertThat(metrics.get("historyTotal").asInt()).isEqualTo(105);
        // Still oldest first for the chart, and the old end is what was dropped: none of
        // the three earliest weigh-ins made the cut.
        assertNewestFirst(reversed(history), "loggedAt");
        for (JsonNode point : history) {
            assertThat(point.get("weight").asDouble()).isGreaterThan(84.0);
        }
        // Nothing is lost — the full record is still reachable page by page.
        JsonNode all = json(send(getAuth("/api/health/metrics/" + userId + "/history?size=100&page=1", jwt)));
        assertThat(all.get("content")).hasSize(5);
    }

    @Test
    @Order(8)
    @DisplayName("Every paged endpoint treats a negative page as page 0, not a 500")
    void negativePageIsPageZeroEverywhere() throws Exception {
        for (String path : new String[]{
                "/api/activities?page=-1",
                "/api/nutrition/meals?page=-1",
                "/api/notifications?page=-1",
                "/api/health/sleep/" + userId + "/history?page=-1",
                "/api/health/metrics/" + userId + "/history?page=-1"}) {
            HttpResponse<String> response = send(getAuth(path, jwt));
            assertThat(response.statusCode()).as(path).isEqualTo(200);
            assertThat(json(response).get("page").get("number").asInt()).as(path).isZero();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void assertNewestFirst(JsonNode rows, String field) {
        for (int i = 1; i < rows.size(); i++) {
            String previous = rows.get(i - 1).get(field).asText();
            String current = rows.get(i).get(field).asText();
            assertThat(previous.compareTo(current)).as("row %d not after row %d", i - 1, i)
                    .isGreaterThanOrEqualTo(0);
        }
    }

    private JsonNode reversed(JsonNode array) {
        var copy = objectMapper.createArrayNode();
        for (int i = array.size() - 1; i >= 0; i--) copy.add(array.get(i));
        return copy;
    }

    private String[] register(String email) throws Exception {
        RegisterRequest reg = new RegisterRequest();
        reg.setEmail(email);
        reg.setPassword("testPass123");
        reg.setFirstName("Page");
        reg.setLastName("Tester");
        HttpResponse<String> regResp = send(post("/api/auth/register", reg));
        assertThat(regResp.statusCode()).isEqualTo(200);
        String id = json(regResp).get("id").asText();

        LoginRequest login = new LoginRequest();
        login.setEmail(email);
        login.setPassword("testPass123");
        String token = json(send(post("/api/auth/login", login))).get("accessToken").asText();
        return new String[]{id, token};
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

    private HttpRequest getAuth(String path, String token) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url(path)))
                .header("Authorization", "Bearer " + token)
                .GET().build();
    }
}
