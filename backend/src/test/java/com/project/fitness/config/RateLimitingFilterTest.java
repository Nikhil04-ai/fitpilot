package com.project.fitness.config;

import com.project.fitness.security.JwtUtils;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("RateLimitingFilter Tests")
class RateLimitingFilterTest {

    private static final String SECRET =
            "dGVzdC1zZWNyZXQta2V5LWZvci11bml0LXRlc3RzLXRoYXQtaXMtbG9uZy1lbm91Z2gtMjU2LWJpdHM=";

    /** The production numbers, so the tiers are exercised at their real capacities. */
    private static final int AUTH_LIMIT = 10;
    private static final int AI_LIMIT = 10;
    private static final int STANDARD_LIMIT = 100;
    private static final int INVITE_LIMIT = 10;

    private JwtUtils jwtUtils;
    private RateLimitingFilter filter;

    @BeforeEach
    void setUp() {
        jwtUtils = new JwtUtils();
        ReflectionTestUtils.setField(jwtUtils, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(jwtUtils, "jwtExpirationMs", 60_000);
        filter = new RateLimitingFilter(jwtUtils, AUTH_LIMIT, AI_LIMIT, STANDARD_LIMIT, INVITE_LIMIT);
    }

    private MockHttpServletResponse call(String method, String uri, String token) throws Exception {
        return call(method, uri, token, "10.0.0.1");
    }

    private MockHttpServletResponse call(String method, String uri, String token, String ip) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        request.setRemoteAddr(ip);
        if (token != null) request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, mock(FilterChain.class));
        return response;
    }

    /**
     * The auth bucket was keyed on the first X-Forwarded-For entry, which the client
     * writes, so a new value per request meant a new bucket per request: 25 password
     * guesses in a row all went through against the live app. The header is ignored
     * now; behind a proxy the container resolves the address (forward-headers-strategy).
     */
    @Test
    @DisplayName("A client-written X-Forwarded-For does not get a fresh auth bucket")
    void spoofedForwardedForDoesNotResetTheAuthBucket() throws Exception {
        for (int i = 0; i < AUTH_LIMIT; i++) {
            assertThat(callForwardedFor("/api/auth/login", "203.0.113." + i).getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse blocked = callForwardedFor("/api/auth/login", "198.51.100.77");
        assertThat(blocked.getStatus()).isEqualTo(429);
    }

    private MockHttpServletResponse callForwardedFor(String uri, String forwardedFor) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        request.setRemoteAddr("10.0.0.9");
        request.addHeader("X-Forwarded-For", forwardedFor);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, mock(FilterChain.class));
        return response;
    }

    @Test
    @DisplayName("CORS preflight is never rate-limited")
    void optionsRequestPassesThrough() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/activities");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getHeader("X-RateLimit-Limit")).isNull();
    }

    /**
     * The tier lookup used to be an allowlist, so every route nobody had listed was
     * uncapped: friends, challenges, notifications, goals, health and more (PLAN P4-5).
     */
    @Test
    @DisplayName("Every /api route is limited, including the ones nobody listed")
    void everyApiRouteIsLimited() throws Exception {
        String token = jwtUtils.generateToken("everywhere", "USER");
        for (String uri : new String[] {"/api/goals/everywhere", "/api/friends", "/api/challenges/user/everywhere",
                "/api/notifications/unread-count", "/api/health/sleep/everywhere", "/api/profile/everywhere",
                "/api/stats/weekly/everywhere", "/api/admin/users"}) {
            assertThat(call("GET", uri, token).getHeader("X-RateLimit-Limit"))
                    .as(uri).isEqualTo(String.valueOf(STANDARD_LIMIT));
        }
    }

    @Test
    @DisplayName("Paths outside /api — the health check and Swagger — are not counted")
    void nonApiPathsAreNotLimited() throws Exception {
        assertThat(call("GET", "/actuator/health", null).getHeader("X-RateLimit-Limit")).isNull();
        assertThat(call("GET", "/swagger-ui/index.html", null).getHeader("X-RateLimit-Limit")).isNull();
    }

    @Test
    @DisplayName("Friend requests run out after the invite cap; the rest of the API carries on")
    void friendRequestsHitTheInviteCap() throws Exception {
        String token = jwtUtils.generateToken("spammer", "USER");
        for (int i = 0; i < INVITE_LIMIT; i++) {
            assertThat(call("POST", "/api/friends/request", token).getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse blocked = call("POST", "/api/friends/request", token);
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getContentAsString()).contains("Too many invitations");

        // A separate bucket: reading the friends list, or answering a request, still works.
        assertThat(call("GET", "/api/friends", token).getStatus()).isEqualTo(200);
        assertThat(call("POST", "/api/friends/f-1/accept", token).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Creating a challenge shares the invite bucket; responding to one does not")
    void challengeCreationIsAnInvite() throws Exception {
        String token = jwtUtils.generateToken("challenger", "USER");
        for (int i = 0; i < INVITE_LIMIT / 2; i++) call("POST", "/api/friends/request", token);
        for (int i = 0; i < INVITE_LIMIT / 2; i++) call("POST", "/api/challenges", token);

        assertThat(call("POST", "/api/challenges", token).getStatus()).isEqualTo(429);
        assertThat(call("POST", "/api/challenges/c-1/respond", token).getStatus()).isEqualTo(200);
        assertThat(call("GET", "/api/challenges/user/challenger", token)
                .getHeader("X-RateLimit-Limit")).isEqualTo(String.valueOf(STANDARD_LIMIT));
    }

    @Test
    @DisplayName("One user's spent invite budget doesn't touch another user's")
    void inviteBucketsArePerUser() throws Exception {
        String spammer = jwtUtils.generateToken("spammer-2", "USER");
        String other = jwtUtils.generateToken("bystander", "USER");
        for (int i = 0; i <= INVITE_LIMIT; i++) call("POST", "/api/friends/request", spammer);

        assertThat(call("POST", "/api/friends/request", spammer).getStatus()).isEqualTo(429);
        assertThat(call("POST", "/api/friends/request", other).getStatus()).isEqualTo(200);
    }

    /**
     * The bucket used to be keyed on the tail of the access token. Access tokens
     * rotate on every refresh, so a caller got a fresh, full bucket each time —
     * the limit could be reset at will, and the old entry was never reclaimed.
     * Two different tokens for the same user must share one bucket.
     */
    @Test
    @DisplayName("Two tokens for the same user share a single bucket")
    void differentTokensSameUserShareBucket() throws Exception {
        String tokenA = jwtUtils.generateToken("user-1", "USER");
        Thread.sleep(1_100);   // second-resolution `iat` — force a distinct token
        String tokenB = jwtUtils.generateToken("user-1", "USER");
        assertThat(tokenA).isNotEqualTo(tokenB);

        int remainingAfterFirst = Integer.parseInt(
                call("GET", "/api/activities", tokenA).getHeader("X-RateLimit-Remaining"));
        int remainingAfterSecond = Integer.parseInt(
                call("GET", "/api/activities", tokenB).getHeader("X-RateLimit-Remaining"));

        assertThat(remainingAfterSecond).isEqualTo(remainingAfterFirst - 1);
    }

    @Test
    @DisplayName("Different users get independent buckets")
    void differentUsersDoNotShareBucket() throws Exception {
        String tokenA = jwtUtils.generateToken("user-1", "USER");
        String tokenB = jwtUtils.generateToken("user-2", "USER");

        int remainingA = Integer.parseInt(
                call("GET", "/api/activities", tokenA).getHeader("X-RateLimit-Remaining"));
        int remainingB = Integer.parseInt(
                call("GET", "/api/activities", tokenB).getHeader("X-RateLimit-Remaining"));

        assertThat(remainingA).isEqualTo(remainingB);
    }

    @Test
    @DisplayName("The 101st request in a minute is rejected with 429 and Retry-After")
    void exceedingTheLimitReturns429() throws Exception {
        String token = jwtUtils.generateToken("burst-user", "USER");

        for (int i = 0; i < 100; i++) {
            assertThat(call("POST", "/api/activities", token).getStatus()).isEqualTo(200);
        }

        MockHttpServletResponse limited = call("POST", "/api/activities", token);
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isEqualTo("60");
        assertThat(limited.getContentAsString()).contains("Rate limit exceeded");
    }

    @Test
    @DisplayName("Unauthenticated requests fall back to an IP-based bucket")
    void unauthenticatedRequestsAreLimitedByIp() throws Exception {
        assertThat(call("GET", "/api/activities", null).getHeader("X-RateLimit-Remaining"))
                .isNotNull();
    }

    // -- Tiers (PLAN P1-5: login, register and the AI routes used to be uncapped) --

    @Test
    @DisplayName("Login is capped far below the standard allowance")
    void loginIsRateLimited() throws Exception {
        assertThat(call("POST", "/api/auth/login", null).getHeader("X-RateLimit-Limit"))
                .isEqualTo(String.valueOf(AUTH_LIMIT));
    }

    @Test
    @DisplayName("Credential stuffing runs out of attempts after the auth cap")
    void loginBurstIsRejected() throws Exception {
        for (int i = 0; i < AUTH_LIMIT; i++) {
            assertThat(call("POST", "/api/auth/login", null).getStatus()).isEqualTo(200);
        }

        MockHttpServletResponse limited = call("POST", "/api/auth/login", null);
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isEqualTo("60");
        assertThat(limited.getContentAsString()).contains("Too many authentication attempts");
    }

    @Test
    @DisplayName("Register shares the auth bucket, so signup cannot dodge the login cap")
    void registerSharesTheAuthBucket() throws Exception {
        for (int i = 0; i < AUTH_LIMIT; i++) {
            call("POST", "/api/auth/register", null);
        }
        assertThat(call("POST", "/api/auth/login", null).getStatus()).isEqualTo(429);
    }

    /**
     * The auth tier is keyed by IP even when a token is present. Keying on the token
     * would let anyone holding one valid account reset the bucket at will, which is
     * exactly the caller a login limit exists to stop.
     */
    @Test
    @DisplayName("Presenting a valid token does not buy a fresh auth bucket")
    void authBucketIgnoresTheToken() throws Exception {
        for (int i = 0; i < AUTH_LIMIT; i++) {
            call("POST", "/api/auth/login", null);
        }

        String token = jwtUtils.generateToken("user-1", "USER");
        assertThat(call("POST", "/api/auth/login", token).getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("A different IP gets its own auth bucket")
    void authBucketsArePerIp() throws Exception {
        for (int i = 0; i < AUTH_LIMIT; i++) {
            call("POST", "/api/auth/login", null, "10.0.0.1");
        }
        assertThat(call("POST", "/api/auth/login", null, "10.0.0.2").getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("AI-backed routes get their own, much smaller allowance")
    void aiRoutesAreLimited() throws Exception {
        String token = jwtUtils.generateToken("ai-user", "USER");

        assertThat(call("POST", "/api/recommendation/generate", token).getHeader("X-RateLimit-Limit"))
                .isEqualTo(String.valueOf(AI_LIMIT));

        for (int i = 1; i < AI_LIMIT; i++) {
            assertThat(call("POST", "/api/recommendation/generate", token).getStatus()).isEqualTo(200);
        }
        assertThat(call("POST", "/api/recommendation/generate", token).getStatus()).isEqualTo(429);
    }

    /**
     * /api/activities/natural is a Gemini call that happens to live under the
     * activities prefix - it must not inherit the standard 100/min allowance.
     */
    @Test
    @DisplayName("Natural-language logging counts against the AI budget, not the activity one")
    void naturalLanguageIsAnAiRoute() throws Exception {
        String token = jwtUtils.generateToken("nl-user", "USER");

        assertThat(call("POST", "/api/activities/natural", token).getHeader("X-RateLimit-Limit"))
                .isEqualTo(String.valueOf(AI_LIMIT));

        for (int i = 1; i < AI_LIMIT; i++) {
            call("POST", "/api/activities/natural", token);
        }
        assertThat(call("POST", "/api/activities/natural", token).getStatus()).isEqualTo(429);

        // ...and the ordinary activity route is untouched by that burst.
        assertThat(call("GET", "/api/activities", token).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Reading recommendations is not throttled as if it were generating them")
    void readingRecommendationsUsesNoAiBudget() throws Exception {
        String token = jwtUtils.generateToken("reader", "USER");
        assertThat(call("GET", "/api/recommendation/user/reader", token).getHeader("X-RateLimit-Limit"))
                .isEqualTo(String.valueOf(STANDARD_LIMIT));
    }

    @Test
    @DisplayName("Auth, AI and standard budgets are spent independently")
    void tiersDoNotShareBuckets() throws Exception {
        String token = jwtUtils.generateToken("mixed", "USER");

        for (int i = 0; i < AUTH_LIMIT; i++) call("POST", "/api/auth/login", null);
        assertThat(call("POST", "/api/auth/login", null).getStatus()).isEqualTo(429);

        assertThat(call("GET", "/api/activities", token).getStatus()).isEqualTo(200);
        assertThat(call("POST", "/api/recommendation/generate", token).getStatus()).isEqualTo(200);
    }
}
