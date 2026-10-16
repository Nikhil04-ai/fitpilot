package com.project.fitness.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.fitness.dto.LoginRequest;
import com.project.fitness.dto.RegisterRequest;
import com.project.fitness.service.EmailService;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Password reset over real HTTP (PLAN P1-4).
 *
 * <p>{@link EmailService} is replaced so the link can be read out of what would have
 * been sent — nothing else about the flow is stubbed, and only the hash reaches the
 * database, so there is no other way to obtain the token.
 *
 * <p>Plain {@code java.net.http.HttpClient}: TestRestTemplate and MockMvc both moved
 * artifact in Spring Boot 4.1 and broke this build twice (AGENT.md rule 8).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Password Reset Integration Tests")
class PasswordResetIntegrationTest {

    private static final String EMAIL = "resettest@test.com";
    private static final String OLD_PASSWORD = "originalPass123";
    private static final String NEW_PASSWORD = "replacementPass456";

    @LocalServerPort private int port;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private EmailService emailService;

    private final HttpClient http = HttpClient.newHttpClient();

    private String usedToken;

    @BeforeAll
    void registerAccount() throws Exception {
        RegisterRequest reg = new RegisterRequest();
        reg.setEmail(EMAIL);
        reg.setPassword(OLD_PASSWORD);
        reg.setFirstName("Reset");
        reg.setLastName("Tester");
        assertThat(send(post("/api/auth/register", reg)).statusCode()).isEqualTo(200);
    }

    // ── Requesting ───────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("An unknown address gets the same answer as a real one")
    void doesNotRevealWhetherAnAccountExists() throws Exception {
        HttpResponse<String> unknown = send(post("/api/auth/forgot-password",
                Map.of("email", "definitely-not-a-user@test.com")));
        HttpResponse<String> known = send(post("/api/auth/forgot-password",
                Map.of("email", EMAIL)));

        // Same status and same body — otherwise this endpoint is a free lookup for
        // whether any given address has an account here.
        assertThat(unknown.statusCode()).isEqualTo(known.statusCode()).isEqualTo(200);
        assertThat(unknown.body()).isEqualTo(known.body());
        awaitEmails(1);   // queued after the reply; let it finish inside this test
    }

    // ── Completing ───────────────────────────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("The emailed link resets the password and the new one works")
    void resetWithTheEmailedLink() throws Exception {
        // Signed in before the reset, as whoever the reset is meant to lock out would be.
        JsonNode before = objectMapper.readTree(login(OLD_PASSWORD).body());
        String userId = before.get("user").get("id").asText();
        String oldAccessToken = before.get("accessToken").asText();
        assertThat(profileStatus(userId, oldAccessToken)).isEqualTo(200);

        usedToken = requestResetAndReadEmail();
        assertThat(usedToken).isNotBlank();

        HttpResponse<String> reset = send(post("/api/auth/reset-password",
                Map.of("token", usedToken, "newPassword", NEW_PASSWORD)));
        assertThat(reset.statusCode()).isEqualTo(200);

        assertThat(login(NEW_PASSWORD).statusCode()).as("new password works").isEqualTo(200);
        assertThat(login(OLD_PASSWORD).statusCode()).as("old password is dead").isEqualTo(401);

        // The reset also ends sessions that were already open: a refresh token is gone,
        // and now so is the access token, which used to live out its 48 hours (PLAN P4-3).
        assertThat(profileStatus(userId, oldAccessToken)).as("old access token").isEqualTo(401);
    }

    @Test
    @Order(3)
    @DisplayName("The same link cannot be used twice")
    void tokenIsSingleUse() throws Exception {
        HttpResponse<String> replay = send(post("/api/auth/reset-password",
                Map.of("token", usedToken, "newPassword", "yetAnotherPass789")));

        assertThat(replay.statusCode()).isEqualTo(400);
        assertThat(replay.body()).contains("expired or already been used");
        assertThat(login(NEW_PASSWORD).statusCode()).as("password unchanged").isEqualTo(200);
    }

    @Test
    @Order(4)
    @DisplayName("A made-up token is refused, and says nothing about why")
    void unknownTokenRefused() throws Exception {
        HttpResponse<String> response = send(post("/api/auth/reset-password",
                Map.of("token", "clearly-not-a-real-token", "newPassword", "somethingLong123")));

        assertThat(response.statusCode()).isEqualTo(400);
        // Identical to the replay message above: a caller must not be able to tell a
        // token that never existed from one that has been spent.
        assertThat(response.body()).contains("expired or already been used");
    }

    @Test
    @Order(5)
    @DisplayName("A too-short password is rejected with a message that says so")
    void shortPasswordRejected() throws Exception {
        String fresh = requestResetAndReadEmail();

        HttpResponse<String> response = send(post("/api/auth/reset-password",
                Map.of("token", fresh, "newPassword", "abc")));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("at least 8 characters");
    }

    @Test
    @Order(6)
    @DisplayName("Asking again invalidates the previous link")
    void requestingAgainInvalidatesTheOldLink() throws Exception {
        String first = requestResetAndReadEmail();
        String second = requestResetAndReadEmail();
        assertThat(second).isNotEqualTo(first);

        assertThat(send(post("/api/auth/reset-password",
                Map.of("token", first, "newPassword", "afterSupersede1"))).statusCode())
                .as("the superseded link is dead").isEqualTo(400);

        assertThat(send(post("/api/auth/reset-password",
                Map.of("token", second, "newPassword", "afterSupersede1"))).statusCode())
                .as("the newest link works").isEqualTo(200);
    }

    @Test
    @Order(7)
    @DisplayName("Reset endpoints are reachable without a token, since that is the point")
    void endpointsArePublic() throws Exception {
        // Under the permitAll /api/auth/** prefix by necessity — someone who has
        // forgotten their password cannot authenticate first. Anything other than 401
        // or 403 proves the route is not behind the JWT filter.
        assertThat(send(post("/api/auth/forgot-password", Map.of("email", "x@y.com"))).statusCode())
                .isEqualTo(200);
    }

    /**
     * The reply used to wait for the token to be written and the email sent, so a real
     * address was answered later than an unknown one and the delay gave the account away
     * (PLAN P5-12). With mail switched on, that delay is the provider's whole round trip.
     */
    @Test
    @Order(8)
    @DisplayName("A real account is answered without waiting for the email to be sent")
    void replyDoesNotWaitForTheWork() throws Exception {
        doAnswer(invocation -> { Thread.sleep(1500); return true; })
                .when(emailService).send(anyString(), anyString(), anyString());

        long start = System.nanoTime();
        HttpResponse<String> response = send(post("/api/auth/forgot-password", Map.of("email", EMAIL)));
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(millis).as("reply time with a 1.5 s mail provider").isLessThan(1000);
        awaitEmails(1);   // the work still happens, just after the reply
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Asks for a reset link for EMAIL and returns its token. The request is queued and
     * answered at once, so this waits for that request's email to be handed over.
     */
    private String requestResetAndReadEmail() throws Exception {
        long before = emailsSent();
        assertThat(send(post("/api/auth/forgot-password", Map.of("email", EMAIL))).statusCode()).isEqualTo(200);
        awaitEmails((int) before + 1);
        return tokenFromLatestEmail();
    }

    /** Waits up to 5 s for exactly this many emails since the mock was last reset. */
    private void awaitEmails(int count) {
        verify(emailService, timeout(5000).times(count)).send(anyString(), anyString(), anyString());
    }

    private long emailsSent() {
        return mockingDetails(emailService).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("send")).count();
    }

    /** Pulls the raw token out of the most recent email body the service handed over. */
    private String tokenFromLatestEmail() {
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(emailService, org.mockito.Mockito.atLeastOnce())
                .send(anyString(), anyString(), html.capture());

        String body = html.getAllValues().get(html.getAllValues().size() - 1);
        int start = body.indexOf("?reset=") + "?reset=".length();
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }

    private int profileStatus(String userId, String accessToken) throws Exception {
        return send(HttpRequest.newBuilder().uri(URI.create(url("/api/profile/" + userId)))
                .header("Authorization", "Bearer " + accessToken).GET().build()).statusCode();
    }

    private HttpResponse<String> login(String password) throws Exception {
        LoginRequest login = new LoginRequest();
        login.setEmail(EMAIL);
        login.setPassword(password);
        return send(post("/api/auth/login", login));
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
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
}
