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
 * Integration test — linking a pain log to a workout (PLAN P4-6).
 *
 * <p>{@code activityId} had no ownership check, so a pain log could point at somebody
 * else's activity. Responses never show the linked activity, so nothing leaked — but
 * the foreign key has no ON DELETE rule, so the owner could then never delete that
 * workout. Deleting one's own linked workout failed the same way.
 *
 * <p>Per AGENT.md §7, plain java.net.http.HttpClient — not TestRestTemplate.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Pain Log Integration Tests")
class PainLogIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();

    private String ownerJwt, ownerId, otherJwt;

    @BeforeAll
    void setUp() throws Exception {
        ownerId = register("pain-owner@test.com");
        register("pain-other@test.com");
        ownerJwt = login("pain-owner@test.com");
        otherJwt = login("pain-other@test.com");
    }

    @Test
    @DisplayName("A pain log can't be linked to another user's workout")
    void cannotLinkSomeoneElsesActivity() throws Exception {
        String ownersWorkout = logActivity(ownerJwt);

        HttpResponse<String> response = send(postAuth(otherJwt, "/api/pain-log", pain(ownersWorkout)));

        assertThat(response.statusCode()).isEqualTo(403);
        // And because nothing was linked, the owner can still delete the workout.
        assertThat(send(deleteAuth(ownerJwt, "/api/activities/" + ownersWorkout)).statusCode()).isEqualTo(204);
    }

    @Test
    @DisplayName("An activity id that doesn't exist is a 400, not a silently unlinked entry")
    void unknownActivityIsRejected() throws Exception {
        HttpResponse<String> response = send(postAuth(ownerJwt, "/api/pain-log", pain("no-such-activity")));
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("Activity not found");
    }

    @Test
    @DisplayName("Linking your own workout works, and with no activity id the entry is unlinked")
    void ownActivityAndNoActivityBothWork() throws Exception {
        String workout = logActivity(ownerJwt);
        assertThat(send(postAuth(ownerJwt, "/api/pain-log", pain(workout))).statusCode()).isEqualTo(200);
        assertThat(send(postAuth(ownerJwt, "/api/pain-log", pain(null))).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("Deleting a workout keeps the pain logged against it, unlinked")
    void deletingALinkedWorkoutKeepsThePainLog() throws Exception {
        String workout = logActivity(ownerJwt);
        JsonNode logged = json(send(postAuth(ownerJwt, "/api/pain-log", pain(workout))));
        String painId = logged.get("id").asText();

        // Used to fail on the foreign key: the workout could never be deleted.
        assertThat(send(deleteAuth(ownerJwt, "/api/activities/" + workout)).statusCode()).isEqualTo(204);

        JsonNode history = json(send(getAuth(ownerJwt, "/api/pain-log/" + ownerId + "?page=0&size=100")));
        boolean stillThere = false;
        for (JsonNode entry : history.get("content")) {
            if (painId.equals(entry.get("id").asText())) stillThere = true;
        }
        assertThat(stillThere).as("pain log %s survives its workout", painId).isTrue();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> pain(String activityId) {
        Map<String, Object> body = new HashMap<>();
        body.put("bodyPart", "LEFT_KNEE");
        body.put("severity", 3);
        body.put("description", "twinge on the stairs");
        body.put("activityId", activityId);
        return body;
    }

    private String logActivity(String jwt) throws Exception {
        HttpResponse<String> response = send(postAuth(jwt, "/api/activities", Map.of(
                "type", "RUNNING", "duration", 30, "startTime", LocalDateTime.now().toString())));
        assertThat(response.statusCode()).isEqualTo(200);
        return json(response).get("id").asText();
    }

    private String register(String email) throws Exception {
        HttpResponse<String> response = send(post("/api/auth/register", Map.of(
                "email", email, "password", "testPass123", "firstName", "Pain", "lastName", "Tester",
                "weight", 70.0, "height", 175.0, "age", 30)));
        assertThat(response.statusCode()).isEqualTo(200);
        return json(response).get("id").asText();
    }

    private String login(String email) throws Exception {
        return json(send(post("/api/auth/login", Map.of("email", email, "password", "testPass123"))))
                .get("accessToken").asText();
    }

    private String url(String path) { return "http://localhost:" + port + path; }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return objectMapper.readTree(response.body());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest post(String path, Object body) throws Exception {
        return HttpRequest.newBuilder().uri(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))).build();
    }

    private HttpRequest postAuth(String jwt, String path, Object body) throws Exception {
        return HttpRequest.newBuilder().uri(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + jwt)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))).build();
    }

    private HttpRequest getAuth(String jwt, String path) {
        return HttpRequest.newBuilder().uri(URI.create(url(path)))
                .header("Authorization", "Bearer " + jwt).GET().build();
    }

    private HttpRequest deleteAuth(String jwt, String path) {
        return HttpRequest.newBuilder().uri(URI.create(url(path)))
                .header("Authorization", "Bearer " + jwt).DELETE().build();
    }
}
