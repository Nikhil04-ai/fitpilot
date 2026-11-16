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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — a weight change anywhere is a weigh-in, and BMI agrees with itself.
 *
 * <p>Found by the full-app test run of 2026-11-16:
 * <ul>
 *   <li>The profile is where the app lets a person change their weight, and it wrote no
 *       weigh-in row. So the weight chart could never fill from the UI, and a weight goal
 *       kept reading the last weigh-in while Health showed the new weight.</li>
 *   <li>The BMI category was judged from the rounded BMI and the healthy range's top
 *       used 24.9, so at the edges one response contradicted itself.</li>
 * </ul>
 *
 * <p>Per AGENT.md §7, plain java.net.http.HttpClient — not TestRestTemplate.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Body weight and BMI")
class BodyWeightIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newHttpClient();
    private String jwt, userId;

    @BeforeEach
    void freshUser() throws Exception {
        String email = "weight-" + UUID.randomUUID() + "@test.com";
        userId = json(send(request("POST", "/api/auth/register", Map.of("email", email, "password", "testPass123",
                "firstName", "We", "lastName", "Ight", "weight", 80.0, "height", 180.0, "age", 30), null))).get("id").asText();
        jwt = json(send(request("POST", "/api/auth/login", Map.of("email", email, "password", "testPass123"), null)))
                .get("accessToken").asText();
    }

    @Test
    @DisplayName("Changing weight in the profile records a weigh-in; saving a name or the same weight doesn't")
    void profileWeightChangeIsAWeighIn() throws Exception {
        for (double kg : new double[] {79.0, 78.5, 77.0}) {
            assertThat(send(request("PUT", "/api/profile/" + userId, Map.of("weight", kg), jwt)).statusCode()).isEqualTo(200);
        }
        send(request("PUT", "/api/profile/" + userId, Map.of("firstName", "Renamed"), jwt));     // no weight
        send(request("PUT", "/api/profile/" + userId, Map.of("weight", 77.0), jwt));             // unchanged

        JsonNode metrics = json(send(request("GET", "/api/health/metrics/" + userId, null, jwt)));
        assertThat(metrics.get("historyTotal").asInt()).isEqualTo(3);
        assertThat(metrics.get("history").get(0).get("weight").asDouble()).isEqualTo(79.0);
        assertThat(metrics.get("history").get(2).get("weight").asDouble()).isEqualTo(77.0);
        assertThat(metrics.get("weight").asDouble()).isEqualTo(77.0);
    }

    @Test
    @DisplayName("A weight goal follows a weight changed in the profile, as Health does")
    void weightGoalFollowsTheProfile() throws Exception {
        send(request("POST", "/api/goals", Map.of("type", "TARGET_WEIGHT", "targetValue", 75), jwt));
        send(request("PUT", "/api/health/metrics/" + userId, Map.of("weight", 79.0), jwt));
        send(request("PUT", "/api/profile/" + userId, Map.of("weight", 74.0), jwt));

        JsonNode goal = json(send(request("GET", "/api/goals/" + userId, null, jwt))).get(0);
        assertThat(goal.get("currentValue").asDouble()).isEqualTo(74.0);
        assertThat(goal.get("isCompleted").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("The BMI figure, its category and the healthy range agree at the boundaries")
    void bmiAgreesWithItself() throws Exception {
        JsonNode low = metrics(53.4, 170.0);     // BMI 18.48
        assertThat(low.get("bmiCategory").asText()).isEqualTo("Underweight");
        assertThat(low.get("bmi").asDouble()).isEqualTo(18.4);
        assertThat(low.get("healthyWeightRange").asText()).isEqualTo("53.5 kg – 72.2 kg");

        assertThat(metrics(53.5, 170.0).get("bmiCategory").asText()).isEqualTo("Normal weight");

        JsonNode high = metrics(72.2, 170.0);    // BMI 24.98: still normal, and inside the range
        assertThat(high.get("bmiCategory").asText()).isEqualTo("Normal weight");
        assertThat(high.get("bmi").asDouble()).isEqualTo(24.9);

        JsonNode over = metrics(72.3, 170.0);    // BMI 25.02
        assertThat(over.get("bmiCategory").asText()).isEqualTo("Overweight");
        assertThat(over.get("bmi").asDouble()).isEqualTo(25.0);
    }

    private JsonNode metrics(double kg, double cm) throws Exception {
        HttpResponse<String> r = send(request("PUT", "/api/health/metrics/" + userId, Map.of("weight", kg, "height", cm), jwt));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json(r);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String url(String path) { return "http://localhost:" + port + path; }

    private JsonNode json(HttpResponse<String> r) throws Exception { return objectMapper.readTree(r.body()); }

    private HttpResponse<String> send(HttpRequest r) throws Exception {
        return http.send(r, HttpResponse.BodyHandlers.ofString());
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
