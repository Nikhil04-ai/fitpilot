package com.project.fitness.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.fitness.repository.ChallengeRepository;
import com.project.fitness.service.ChallengeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — a notification that asks the user to act is taken back once there is
 * nothing left to act on (PLAN P6-F16).
 *
 * <p>Found by the full-app run of 2026-11-16: a withdrawn friend request or a cancelled
 * challenge invite stayed unread in the feed, counted in the bell's badge, and Open led to a
 * panel with nothing to answer. Every way a request or an invite can end is covered here,
 * and in each the other notifications in the feed are left alone.
 *
 * <p>Per AGENT.md §7, plain java.net.http.HttpClient — not TestRestTemplate.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Notifications are taken back when what they ask about is gone (P6-F16)")
class NotificationRetirementIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ChallengeRepository challengeRepository;
    @Autowired private ChallengeService challengeService;
    @Autowired private PlatformTransactionManager transactionManager;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("A withdrawn friend request leaves no notification behind")
    void withdrawnRequest() throws Exception {
        String[] a = register("Alice"), b = register("Bob");
        ok(call("POST", "/api/friends/request", Map.of("email", b[2]), a[1]));
        assertThat(titles(b)).containsExactly("Friend request from Alice Test");

        String friendship = json(ok(call("GET", "/api/friends/sent", null, a[1]))).get(0).get("friendshipId").asText();
        noContent(call("DELETE", "/api/friends/" + friendship, null, a[1]));

        assertThat(titles(b)).isEmpty();
        assertThat(unread(b)).isZero();
    }

    @Test
    @DisplayName("A declined request is taken back, and declining then re-sending leaves one, not two")
    void declinedThenResent() throws Exception {
        String[] a = register("Alice"), b = register("Bob");
        ok(call("POST", "/api/friends/request", Map.of("email", b[2]), a[1]));
        noContent(call("DELETE", "/api/friends/" + invite(b), null, b[1]));
        assertThat(titles(b)).isEmpty();

        ok(call("POST", "/api/friends/request", Map.of("email", b[2]), a[1]));
        assertThat(titles(b)).containsExactly("Friend request from Alice Test");
    }

    @Test
    @DisplayName("Accepting a request takes back its notice; the requester still hears it was accepted")
    void acceptedRequest() throws Exception {
        String[] a = register("Alice"), b = register("Bob");
        ok(call("POST", "/api/friends/request", Map.of("email", b[2]), a[1]));
        ok(call("POST", "/api/friends/" + invite(b) + "/accept", null, b[1]));

        assertThat(titles(b)).isEmpty();
        assertThat(titles(a)).containsExactly("Bob Test accepted your friend request");
    }

    @Test
    @DisplayName("Answering a challenge invite, either way, takes back its notification")
    void answeredInvite() throws Exception {
        String[] a = register("Alice"), b = register("Bob"), c = register("Cara");
        makeFriends(a, b);
        makeFriends(a, c);
        String id = challenge(a, b, c);
        assertThat(titles(b)).contains("Alice challenged you");

        ok(call("POST", "/api/challenges/" + id + "/respond", Map.of("accept", true), b[1]));
        ok(call("POST", "/api/challenges/" + id + "/respond", Map.of("accept", false), c[1]));

        assertThat(titles(b)).doesNotContain("Alice challenged you");
        assertThat(titles(c)).doesNotContain("Alice challenged you");
    }

    @Test
    @DisplayName("Cancelling a challenge takes back the invites nobody answered")
    void cancelledChallenge() throws Exception {
        String[] a = register("Alice"), b = register("Bob");
        makeFriends(a, b);
        String id = challenge(a, b);
        int before = unread(b);

        ok(call("DELETE", "/api/challenges/" + id + "/cancel", null, a[1]));

        assertThat(titles(b)).doesNotContain("Alice challenged you");
        assertThat(unread(b)).isEqualTo(before - 1);
    }

    @Test
    @DisplayName("A challenge that runs to its end takes back an invite that was never answered")
    void challengeEndedUnanswered() throws Exception {
        String[] a = register("Alice"), b = register("Bob"), c = register("Cara");
        makeFriends(a, b);
        makeFriends(a, c);
        String id = challenge(a, b, c);
        ok(call("POST", "/api/challenges/" + id + "/respond", Map.of("accept", true), b[1]));

        new TransactionTemplate(transactionManager).executeWithoutResult(s ->
                challengeRepository.findById(id).orElseThrow().setEndDate(LocalDate.now().minusDays(1)));
        challengeService.declareExpiredChallengeWinners();

        assertThat(titles(c)).doesNotContain("Alice challenged you");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String challenge(String[] creator, String[]... opponents) throws Exception {
        List<String> ids = new ArrayList<>();
        for (String[] o : opponents) ids.add(o[0]);
        return json(ok(call("POST", "/api/challenges",
                Map.of("opponentIds", ids, "type", "WORKOUT_COUNT", "durationDays", 7), creator[1]))).get("id").asText();
    }

    private String invite(String[] user) throws Exception {
        return json(ok(call("GET", "/api/friends/invites", null, user[1]))).get(0).get("friendshipId").asText();
    }

    private List<String> titles(String[] user) throws Exception {
        List<String> out = new ArrayList<>();
        json(ok(call("GET", "/api/notifications?size=50", null, user[1]))).get("content")
                .forEach(n -> out.add(n.get("title").asText()));
        return out;
    }

    private int unread(String[] user) throws Exception {
        return json(ok(call("GET", "/api/notifications/unread-count", null, user[1]))).get("unread").asInt();
    }

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
        ok(call("POST", "/api/friends/" + invite(b) + "/accept", null, b[1]));
    }

    private HttpResponse<String> ok(HttpResponse<String> r) {
        assertThat(r.statusCode()).as(r.uri() + " " + r.body()).isEqualTo(200);
        return r;
    }

    /** Removing a friendship (decline, withdraw, unfriend) answers 204. */
    private void noContent(HttpResponse<String> r) {
        assertThat(r.statusCode()).as(r.uri() + " " + r.body()).isEqualTo(204);
    }

    private JsonNode json(HttpResponse<String> r) throws Exception { return objectMapper.readTree(r.body()); }

    private HttpResponse<String> call(String method, String path, Object body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path));
        if (body != null) {
            b.header("Content-Type", "application/json")
             .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
