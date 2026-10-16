package com.project.fitness.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.fitness.security.TokenHash;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — which refresh tokens survive what (PLAN P4-3).
 *
 * <p>Issuing a refresh token used to revoke every other one the user held. That ran on
 * login and on every refresh, so signing in on a phone, or a laptop tab renewing its
 * session, silently ended the session on every other device. Changing the password
 * revoked nothing, so a device signed in by whoever knew the old password stayed in.
 *
 * <p>Per AGENT.md §7, plain java.net.http.HttpClient — not TestRestTemplate.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Session Revocation Integration Tests")
class SessionRevocationIntegrationTest {

    private static final String PASSWORD = "testPass123";

    @LocalServerPort private int port;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    /** Refresh tokens used to be stored as issued, so a read of the table was every account's session (P5-13). */
    @Test
    @DisplayName("The database holds a refresh token's hash, never the token, and the token still works")
    void refreshTokenIsStoredHashed() throws Exception {
        String email = register("hashed");
        String issued = login(email, PASSWORD).get("refreshToken").asText();

        Integer raw = jdbc.queryForObject("select count(*) from refresh_token where token_hash = ?", Integer.class, issued);
        Integer hashed = jdbc.queryForObject("select count(*) from refresh_token where token_hash = ?", Integer.class,
                TokenHash.of(issued));
        assertThat(raw).isZero();
        assertThat(hashed).isEqualTo(1);

        HttpResponse<String> renewed = refresh(issued);
        assertThat(renewed.statusCode()).isEqualTo(200);
        String next = json(renewed).get("refreshToken").asText();
        assertThat(jdbc.queryForObject("select count(*) from refresh_token where token_hash = ?", Integer.class, next))
                .as("the rotated token is stored hashed too").isZero();
    }

    @Test
    @DisplayName("Signing in on a second device leaves the first device's session alone")
    void secondLogin_keepsFirstDeviceSignedIn() throws Exception {
        String email = register("two-devices");
        JsonNode laptop = login(email, PASSWORD);
        JsonNode phone = login(email, PASSWORD);

        assertThat(refresh(laptop.get("refreshToken").asText()).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("One device renewing its session leaves the other device's session alone")
    void refreshOnOneDevice_keepsOtherDeviceSignedIn() throws Exception {
        String email = register("renewal");
        JsonNode laptop = login(email, PASSWORD);
        JsonNode phone = login(email, PASSWORD);

        // The phone renews twice — the frontend does this silently whenever its
        // access token lapses — and the laptop must not notice.
        String phoneToken = phone.get("refreshToken").asText();
        for (int i = 0; i < 2; i++) {
            HttpResponse<String> renewed = refresh(phoneToken);
            assertThat(renewed.statusCode()).isEqualTo(200);
            phoneToken = objectMapper.readTree(renewed.body()).get("refreshToken").asText();
        }
        assertThat(refresh(laptop.get("refreshToken").asText()).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("A refresh token still works exactly once (rotation)")
    void refreshToken_isSingleUse() throws Exception {
        String email = register("rotation");
        String token = login(email, PASSWORD).get("refreshToken").asText();

        HttpResponse<String> first = refresh(token);
        assertThat(first.statusCode()).isEqualTo(200);
        String rotated = objectMapper.readTree(first.body()).get("refreshToken").asText();
        assertThat(rotated).isNotEqualTo(token);

        assertThat(refresh(token).statusCode()).isEqualTo(401);
        assertThat(refresh(rotated).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("Changing the password signs out every other device, but not the one that changed it")
    void passwordChange_revokesOtherSessions() throws Exception {
        String email = register("pw-change");
        JsonNode stolenDevice = login(email, PASSWORD);
        JsonNode me = login(email, PASSWORD);
        String userId = me.get("user").get("id").asText();

        HttpResponse<String> changed = send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/profile/" + userId + "/password")))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + me.get("accessToken").asText())
                .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of(
                        "currentPassword", PASSWORD, "newPassword", "brandNewPass456")))));
        assertThat(changed.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(changed.body());

        // The other device can no longer renew its session.
        assertThat(refresh(stolenDevice.get("refreshToken").asText()).statusCode()).isEqualTo(401);
        // Nor can this device's own pre-change token...
        assertThat(refresh(me.get("refreshToken").asText()).statusCode()).isEqualTo(401);
        // ...because the response carries a fresh pair, which is how it stays signed in.
        assertThat(body.get("accessToken").asText()).isNotBlank();
        assertThat(refresh(body.get("refreshToken").asText()).statusCode()).isEqualTo(200);
    }

    /**
     * Revoking refresh tokens stops a device renewing, but an access token it already
     * holds is a signed JWT that nothing checked against the account, so it kept working
     * for up to 48 hours after the password changed (PLAN P4-3, left open). The token
     * now carries the account's tokenVersion, and a password change bumps it.
     */
    @Test
    @DisplayName("Changing the password kills every access token already issued, but not the fresh one")
    void passwordChange_invalidatesOldAccessTokens() throws Exception {
        String email = register("token-version");
        JsonNode stolen = login(email, PASSWORD);
        JsonNode me = login(email, PASSWORD);
        String userId = me.get("user").get("id").asText();

        assertThat(getProfile(userId, stolen.get("accessToken").asText()).statusCode()).isEqualTo(200);
        assertThat(getProfile(userId, me.get("accessToken").asText()).statusCode()).isEqualTo(200);

        JsonNode changed = changePassword(userId, me.get("accessToken").asText(), PASSWORD, "brandNewPass456");

        // 401, not 403: that is the code the frontend answers by refreshing or signing out.
        assertThat(getProfile(userId, stolen.get("accessToken").asText()).statusCode()).isEqualTo(401);
        assertThat(getProfile(userId, me.get("accessToken").asText()).statusCode()).isEqualTo(401);

        // The pair handed back keeps the changing device signed in...
        assertThat(getProfile(userId, changed.get("accessToken").asText()).statusCode()).isEqualTo(200);
        // ...and an access token minted later by a refresh carries the new version too.
        HttpResponse<String> renewed = refresh(changed.get("refreshToken").asText());
        assertThat(getProfile(userId, json(renewed).get("accessToken").asText()).statusCode()).isEqualTo(200);

        // A second change retires the first fresh token in turn.
        changePassword(userId, changed.get("accessToken").asText(), "brandNewPass456", "anotherPass789");
        assertThat(getProfile(userId, changed.get("accessToken").asText()).statusCode()).isEqualTo(401);
    }

    /**
     * Logout revoked every refresh token but left each device's access token working for
     * up to 48 hours, so "All sessions revoked" was only true of renewal. It now retires
     * the access tokens too, through the same token version a password change bumps.
     */
    @Test
    @DisplayName("Logging out kills every access token already issued, on every device")
    void logout_invalidatesAccessTokens() throws Exception {
        String email = register("logout");
        JsonNode laptop = login(email, PASSWORD);
        JsonNode phone = login(email, PASSWORD);
        String userId = laptop.get("user").get("id").asText();

        assertThat(getProfile(userId, laptop.get("accessToken").asText()).statusCode()).isEqualTo(200);
        assertThat(getProfile(userId, phone.get("accessToken").asText()).statusCode()).isEqualTo(200);

        // As the frontend sends it: the access token, and the refresh token in the body.
        HttpResponse<String> out = send(post("/api/auth/logout",
                Map.of("refreshToken", laptop.get("refreshToken").asText()))
                .header("Authorization", "Bearer " + laptop.get("accessToken").asText()));
        assertThat(out.statusCode()).isEqualTo(200);
        assertThat(out.body()).contains("All sessions revoked");

        assertThat(getProfile(userId, laptop.get("accessToken").asText()).statusCode()).isEqualTo(401);
        assertThat(getProfile(userId, phone.get("accessToken").asText()).statusCode()).isEqualTo(401);
        assertThat(refresh(phone.get("refreshToken").asText()).statusCode()).isEqualTo(401);

        // Logging back in works, at the new version.
        JsonNode again = login(email, PASSWORD);
        assertThat(getProfile(userId, again.get("accessToken").asText()).statusCode()).isEqualTo(200);
    }

    /** A device whose access token had already lapsed proves itself with the refresh token. */
    @Test
    @DisplayName("Logging out with only a refresh token retires the access tokens too")
    void logoutWithRefreshTokenOnly_invalidatesAccessTokens() throws Exception {
        String email = register("logout-refresh-only");
        JsonNode me = login(email, PASSWORD);
        String userId = me.get("user").get("id").asText();

        HttpResponse<String> out = send(post("/api/auth/logout",
                Map.of("refreshToken", me.get("refreshToken").asText())));
        assertThat(out.body()).contains("All sessions revoked");

        assertThat(getProfile(userId, me.get("accessToken").asText()).statusCode()).isEqualTo(401);
    }

    /**
     * Logout ends every session, so the refresh token offered as proof must be live. A
     * spent one used to be accepted until the daily purge deleted it, letting whoever
     * still held it sign the account out at will.
     */
    @Test
    @DisplayName("A refresh token that has been rotated away can no longer sign the account out")
    void logoutWithSpentRefreshToken_endsNothing() throws Exception {
        String email = register("logout-spent");
        JsonNode me = login(email, PASSWORD);
        String userId = me.get("user").get("id").asText();
        String spent = me.get("refreshToken").asText();

        JsonNode renewed = json(refresh(spent));   // rotation revokes the original

        HttpResponse<String> out = send(post("/api/auth/logout", Map.of("refreshToken", spent)));
        assertThat(out.statusCode()).isEqualTo(200);   // logging out is never an error
        assertThat(out.body()).contains("No active session to revoke");

        // The live session is untouched.
        assertThat(getProfile(userId, renewed.get("accessToken").asText()).statusCode()).isEqualTo(200);
        assertThat(refresh(renewed.get("refreshToken").asText()).statusCode()).isEqualTo(200);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private HttpResponse<String> getProfile(String userId, String accessToken) throws Exception {
        return send(HttpRequest.newBuilder().uri(URI.create(url("/api/profile/" + userId)))
                .header("Authorization", "Bearer " + accessToken).GET());
    }

    private JsonNode changePassword(String userId, String accessToken, String current, String next) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/profile/" + userId + "/password")))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(
                        Map.of("currentPassword", current, "newPassword", next)))));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response);
    }

    private String register(String tag) throws Exception {
        String email = "session-" + tag + "@test.com";
        HttpResponse<String> resp = send(post("/api/auth/register", Map.of(
                "email", email, "password", PASSWORD, "firstName", "Sess", "lastName", "Ion")));
        assertThat(resp.statusCode()).isEqualTo(200);
        return email;
    }

    private JsonNode login(String email, String password) throws Exception {
        HttpResponse<String> resp = send(post("/api/auth/login",
                Map.of("email", email, "password", password)));
        assertThat(resp.statusCode()).isEqualTo(200);
        return objectMapper.readTree(resp.body());
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return objectMapper.readTree(response.body());
    }

    private HttpResponse<String> refresh(String refreshToken) throws Exception {
        return send(post("/api/auth/refresh", Map.of("refreshToken", refreshToken)));
    }

    private String url(String path) { return "http://localhost:" + port + path; }

    private HttpRequest.Builder post(String path, Object body) throws Exception {
        return HttpRequest.newBuilder().uri(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
