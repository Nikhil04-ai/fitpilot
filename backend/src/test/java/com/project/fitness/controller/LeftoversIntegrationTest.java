package com.project.fitness.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import com.project.fitness.model.WorkoutPlan;
import com.project.fitness.repository.WorkoutPlanRepository;
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
 * Integration test — the small leftovers of PLAN P4-20.
 *
 * <p>Enrolling in a plan demanded a JSON body it never read. The JSON tests pin what
 * the web layer actually does with unknown fields and dates: JacksonConfig's comments
 * claimed to control that, but the web layer uses Spring's own mapper (Jackson 3), not
 * the Jackson 2 bean JacksonConfig builds, so these behaviours had no test of their own.
 *
 * <p>Per AGENT.md §7, plain java.net.http.HttpClient — not TestRestTemplate.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("P4-20 leftovers Integration Tests")
class LeftoversIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private WorkoutPlanRepository planRepository;

    private final HttpClient http = HttpClient.newHttpClient();

    private String jwt;

    @BeforeAll
    void setUp() throws Exception {
        send(request("POST", "/api/auth/register", Map.of(
                "email", "leftovers@test.com", "password", "testPass123", "firstName", "Left", "lastName", "Over",
                "weight", 70.0, "height", 175.0, "age", 30), null));
        jwt = json(send(request("POST", "/api/auth/login",
                Map.of("email", "leftovers@test.com", "password", "testPass123"), null))).get("accessToken").asText();
    }

    // ── Plan enrolment ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Enrolling in a plan needs no request body, and ignores one if sent")
    void enrollNeedsNoBody() throws Exception {
        // The test profile doesn't seed the system plans, so make one.
        String planId = planRepository.save(WorkoutPlan.builder().name("Enroll test").totalDays(7)
                .difficulty("BEGINNER").goal("ENDURANCE").build()).getId();
        String path = "/api/plans/" + planId + "/enroll";

        // The caller is the JWT's user; nothing in a body is read, so none is required.
        HttpResponse<String> bare = send(HttpRequest.newBuilder().uri(URI.create(url(path)))
                .header("Authorization", "Bearer " + jwt).POST(HttpRequest.BodyPublishers.noBody()).build());
        assertThat(bare.statusCode()).as(bare.body()).isEqualTo(200);

        // Older clients still send a userId. It is ignored, not rejected.
        assertThat(send(request("POST", path, Map.of("userId", "someone-else"), jwt)).statusCode()).isEqualTo(200);
        assertThat(send(request("POST", path, Map.of(), jwt)).statusCode()).isEqualTo(200);
    }

    // ── What the web layer does with JSON ───────────────────────────────────

    @Test
    @DisplayName("A request body with a field the server doesn't know is accepted, and the field ignored")
    void unknownRequestFieldsAreIgnored() throws Exception {
        // The self-register-as-admin fix depends on this: `role` is simply not read.
        HttpResponse<String> response = send(request("POST", "/api/auth/register", Map.of(
                "email", "extrafield@test.com", "password", "testPass123", "role", "ADMIN",
                "somethingInvented", 42), null));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json(response).get("role")).isNull();
    }

    @Test
    @DisplayName("Dates are written as ISO strings, not as arrays of numbers")
    void datesAreIsoStrings() throws Exception {
        JsonNode activity = json(send(request("POST", "/api/activities", Map.of(
                "type", "RUNNING", "duration", 30, "startTime", LocalDateTime.now().toString()), jwt)));

        assertThat(activity.get("startTime").isTextual()).as(activity.get("startTime").toString()).isTrue();
        assertThat(activity.get("startTime").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}.*");
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

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder().uri(URI.create(url(path)))
                .header("Authorization", "Bearer " + jwt).GET().build();
    }
}
