package com.project.fitness.config;

import com.project.fitness.security.JwtUtils;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rate limiting, in four tiers (PLAN P1-5, P4-5).
 *
 * <p>This used to guard exactly one path — {@code /api/activities} — and return early
 * for everything else, which left the two route groups that most needed a limit wide
 * open: {@code /api/auth/login} and {@code /api/auth/register} were uncapped, so
 * credential stuffing and mass signup were both free, and every AI-backed route was
 * uncapped despite each call costing a Gemini request.
 *
 * <p>The tier lookup was an allowlist, so that fix still left every route nobody had
 * listed uncapped: friends, challenges, notifications, goals, health, pain, profile,
 * plans and stats (PLAN P4-5). It now fails closed: every {@code /api/**} route is in
 * some tier, and only paths outside {@code /api} (Swagger, the health check) are not.
 *
 * <p>Returns HTTP 429 with {@code Retry-After} when a bucket is empty.
 *
 * <p>Buckets live in memory, so the limits are per instance. That is the right
 * trade-off for a single Render service; a shared store would be needed before
 * running more than one.
 */
@Slf4j
@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;

    /**
     * Per-minute caps, tunable without a code change.
     *
     * <p>Configurable mainly so the limits can be tightened on a live instance under
     * attack, and loosened where they get in the way — the test profile raises them,
     * because the whole integration suite hits the auth routes from one address and
     * would otherwise rate-limit itself.
     */
    private final int authCapacity;
    private final int aiCapacity;
    private final int standardCapacity;
    private final int inviteCapacity;

    public RateLimitingFilter(JwtUtils jwtUtils,
                              @Value("${app.rate-limit.auth:10}") int authCapacity,
                              @Value("${app.rate-limit.ai:10}") int aiCapacity,
                              @Value("${app.rate-limit.standard:100}") int standardCapacity,
                              @Value("${app.rate-limit.invite:10}") int inviteCapacity) {
        this.jwtUtils = jwtUtils;
        this.authCapacity = authCapacity;
        this.aiCapacity = aiCapacity;
        this.standardCapacity = standardCapacity;
        this.inviteCapacity = inviteCapacity;
    }

    /**
     * A tier of protection: how many requests per minute, and whether the bucket is
     * keyed by caller identity or strictly by IP.
     */
    private enum Tier {
        /**
         * Credentials. Strict, and always IP-keyed: the whole point is to slow down
         * someone who has no valid account yet, and keying on the token would let an
         * attacker holding any one valid token reset the bucket at will.
         */
        AUTH(true, "Too many authentication attempts. Try again in a minute."),

        /**
         * Routes that call out to Gemini. Every request here costs a third-party
         * call against a quota shared by all users, so the cap is low and deliberate.
         */
        AI(false, "Too many AI requests. Try again shortly."),

        /**
         * Requests that put something in another person's feed: a friend request, or
         * a new challenge invite. The standard allowance would let one account push
         * 100 notifications a minute at strangers' inboxes (PLAN P4-5).
         */
        INVITE(false, "Too many invitations. Try again in a minute."),

        /** Ordinary authenticated traffic. */
        STANDARD(false, "Rate limit exceeded. Retry after 60 seconds.");

        final boolean ipOnly;
        final String message;

        Tier(boolean ipOnly, String message) {
            this.ipOnly = ipOnly;
            this.message = message;
        }
    }

    private int capacityOf(Tier tier) {
        return switch (tier) {
            case AUTH -> authCapacity;
            case AI -> aiCapacity;
            case STANDARD -> standardCapacity;
            case INVITE -> inviteCapacity;
        };
    }

    private final Map<String, Entry> buckets = new ConcurrentHashMap<>();

    /** Buckets untouched for this long are dropped — they'd be full again anyway. */
    private static final Duration IDLE_TTL = Duration.ofMinutes(10);
    /** Hard ceiling, so a flood of distinct keys can't exhaust heap. */
    private static final int MAX_BUCKETS = 50_000;
    /** Where an over-full map is cut back to, leaving room before the next sweep. */
    private static final int EVICT_TO = MAX_BUCKETS * 9 / 10;
    private static final long SWEEP_EVERY = 1_000;   // requests between sweeps

    private final AtomicLong requestsSinceSweep = new AtomicLong();

    /** A bucket plus when it was last used, so idle entries can be evicted. */
    private static final class Entry {
        final Bucket bucket;
        volatile Instant lastSeen;
        Entry(Bucket bucket) {
            this.bucket = bucket;
            this.lastSeen = Instant.now();
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // Never rate-limit CORS preflight requests — browsers send these automatically
        // before every cross-origin POST/PUT/DELETE, and they carry no auth header.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        Tier tier = tierFor(request.getMethod(), request.getRequestURI());
        if (tier == null) {
            filterChain.doFilter(request, response);
            return;
        }

        int capacity = capacityOf(tier);
        String clientKey = tier.name() + ":" + resolveClientKey(request, tier.ipOnly);
        Entry entry = buckets.computeIfAbsent(clientKey, k -> new Entry(newBucket(capacity)));
        entry.lastSeen = Instant.now();
        maybeSweep();

        if (entry.bucket.tryConsume(1)) {
            response.setHeader("X-RateLimit-Limit", String.valueOf(capacity));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(entry.bucket.getAvailableTokens()));
            filterChain.doFilter(request, response);
        } else {
            log.warn("Rate limit ({}) exceeded for client: {}", tier, clientKey);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", "60");
            response.setHeader("X-RateLimit-Limit", String.valueOf(capacity));
            response.setHeader("X-RateLimit-Remaining", "0");
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"error\":\"" + tier.message + "\"}");
        }
    }

    /**
     * Which tier a request falls into, or null for paths outside {@code /api}.
     *
     * <p>Order matters: {@code /api/activities/natural} and
     * {@code /api/nutrition/meals/natural} are AI routes that happen to sit under a
     * prefix the standard tier also claims, so both have to be matched first. Falling
     * through would give a Gemini call a 100/min allowance against a third-party quota.
     */
    private Tier tierFor(String method, String uri) {
        if (uri == null) return null;

        // /refresh and /logout are deliberately in here too: refresh takes a bearer
        // token that is guessable in principle, and both are unauthenticated entry
        // points under the permitAll /api/auth/** prefix.
        if (uri.startsWith("/api/auth/")) return Tier.AUTH;

        if (uri.startsWith("/api/activities/natural")
                || uri.startsWith("/api/nutrition/meals/natural")
                || uri.startsWith("/api/recommendation/generate")
                // The weekly digest reasons over a whole week rather than five
                // activities, so it is the most expensive prompt the app sends
                // (PLAN P2-3).
                || uri.startsWith("/api/digest/generate")) {
            return Tier.AI;
        }

        if ("POST".equalsIgnoreCase(method)
                && (uri.equals("/api/friends/request")
                    || uri.equals("/api/challenges") || uri.equals("/api/challenges/"))) {
            return Tier.INVITE;
        }

        // Everything else under /api, including export, the heaviest read in the app
        // (PLAN P2-5). Standard is the right tier for it while the data is the
        // caller's own and the buckets are per user.
        if (uri.startsWith("/api/")) return Tier.STANDARD;

        return null;
    }

    /** N tokens per minute, refilled as a block (Bucket4j 8.x modern API). */
    private Bucket newBucket(int capacity) {
        Bandwidth limit = Bandwidth.builder()
                .capacity(capacity)
                .refillIntervally(capacity, Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    /**
     * Identifies the caller.
     *
     * <p>The user id from the token subject, not the token itself: access tokens rotate
     * (every refresh issues a new one), so keying on the token string handed every
     * caller a brand-new empty bucket on each refresh — the limit was trivially
     * resettable — and left the old entry behind forever.
     *
     * <p>Falls back to the client IP for unauthenticated or unparseable requests, and
     * uses the IP unconditionally when the tier asks for it.
     *
     * <p>This filter runs before JwtAuthenticationFilter (deliberately, so
     * unauthenticated traffic is limited too), which is why the token is read directly
     * here rather than from the SecurityContext.
     */
    private String resolveClientKey(HttpServletRequest request, boolean ipOnly) {
        if (!ipOnly) {
            String jwt = jwtUtils.getJwtFromHeader(request);
            if (jwt != null && jwtUtils.validateJwtToken(jwt)) {
                try {
                    return "user:" + jwtUtils.getUserIdFromToken(jwt);
                } catch (Exception ignored) {
                    // fall through to IP
                }
            }
        }
        return "ip:" + clientIp(request);
    }

    /**
     * The address the connection came from, as the servlet container resolved it.
     *
     * <p>This used to take the first {@code X-Forwarded-For} entry as given. That entry
     * is whatever the client wrote, so sending a different value with each request got
     * a fresh auth bucket every time: password guessing at any speed, and a 50,000-key
     * flood of the bucket map. The header is now never read here. Behind Render's
     * proxy, {@code server.forward-headers-strategy=native} has Tomcat's RemoteIpValve
     * resolve {@link HttpServletRequest#getRemoteAddr()} from it, trusting only entries
     * added by an internal proxy, so a client-written entry is skipped.
     */
    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    /**
     * Drops idle buckets periodically.
     *
     * <p>The map previously only ever grew: one entry per distinct key, never removed,
     * for the lifetime of the process. On a long-running instance that is a slow
     * memory leak, and an easy one to trigger from outside by varying the key.
     */
    private void maybeSweep() {
        if (requestsSinceSweep.incrementAndGet() < SWEEP_EVERY && buckets.size() < MAX_BUCKETS) {
            return;
        }
        requestsSinceSweep.set(0);

        Instant cutoff = Instant.now().minus(IDLE_TTL);
        buckets.entrySet().removeIf(e -> e.getValue().lastSeen.isBefore(cutoff));

        // Still at the ceiling after evicting idle entries (a burst of distinct keys
        // inside the TTL): drop oldest-first down to EVICT_TO. Evicting only down to
        // the ceiling itself, as this used to, left the map full, so the size check
        // above sent every following request through this whole sweep again.
        if (buckets.size() >= MAX_BUCKETS) {
            buckets.entrySet().stream()
                    .sorted((a, b) -> a.getValue().lastSeen.compareTo(b.getValue().lastSeen))
                    .limit(buckets.size() - EVICT_TO)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(buckets::remove);
            log.warn("Rate limiter bucket map hit its ceiling ({}) — evicted oldest entries.", MAX_BUCKETS);
        }
    }
}
