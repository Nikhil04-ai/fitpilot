package com.project.fitness.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.MethodParameter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — every read must tell a dead session to sign in again (PLAN P4-18).
 *
 * <p>A token can outlive its account (a reset database, a deleted user). The frontend
 * recovers from the specific 409 {@code AccountNotFoundException} produces, but a read
 * that never looked the user up answered 200 with an empty page instead, so the person
 * stayed "signed in" to a screen that showed nothing and could not say why.
 *
 * <p>The routes are discovered from Spring's handler mapping, not listed here: a list
 * is exactly what let routes go unchecked before, and a new endpoint is covered the
 * moment it exists. Only routes that don't depend on who is asking are exempt.
 *
 * <p>Per AGENT.md §7, plain java.net.http.HttpClient — not TestRestTemplate.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Dead session — every read")
class DeadSessionReadsIntegrationTest {

    private static final String PHRASE = "account that no longer exists";

    /** Routes whose answer is the same for everyone, so a missing account is irrelevant. */
    private static final Set<String> USER_INDEPENDENT = Set.of(
            "/api/plans",                 // the seeded system plans
            "/api/plans/{planId}",
            "/api/export/datasets");      // a static list

    @LocalServerPort private int port;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    // Named, because the actuator registers a second mapping of the same type.
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("every GET route that depends on the caller returns the 409 for a deleted account")
    void everyReadReturnsTheDeadSession409() throws Exception {
        String email = "deadreads@test.com";
        String userId = json(send(post("/api/auth/register", Map.of(
                "email", email, "password", "testPass123", "firstName", "Dead", "lastName", "Reads",
                "weight", 70.0, "height", 175.0, "age", 30), null))).get("id").asText();
        String jwt = json(send(post("/api/auth/login",
                Map.of("email", email, "password", "testPass123"), null))).get("accessToken").asText();

        // Every caller-dependent read, as paths to hit. Computed once so the two passes
        // below exercise exactly the same routes.
        Map<String, String> routes = new LinkedHashMap<>();   // path -> pattern
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = e.getKey();
            if (!info.getMethodsCondition().getMethods().contains(org.springframework.web.bind.annotation.RequestMethod.GET)) continue;
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                if (!pattern.startsWith("/api/") || pattern.startsWith("/api/auth/")
                        || pattern.startsWith("/api/admin/") || USER_INDEPENDENT.contains(pattern)) continue;
                String path = fill(pattern, e.getValue(), userId);
                if (path != null) routes.put(path + queryFor(e.getValue()), pattern);   // else: about another record
            }
        }
        // A floor, so the discovery can't quietly find nothing and pass.
        assertThat(routes).as("GET routes discovered").hasSizeGreaterThan(30);

        // Control: with the account present, no route may claim it is gone. Without this
        // a guard that rejected everyone would pass the check below.
        List<String> falseAlarms = new ArrayList<>();
        for (Map.Entry<String, String> r : routes.entrySet()) {
            HttpResponse<String> resp = send(get(r.getKey(), jwt));
            if (resp.statusCode() == 409) falseAlarms.add(r.getValue() + " -> 409 for a live account");
        }
        assertThat(falseAlarms).as("routes that rejected a live account").isEmpty();

        // The account vanishes while the token stays valid, as after a database reset.
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        jdbc.update("DELETE FROM fitness_user WHERE id = ?", userId);
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");

        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, String> r : routes.entrySet()) {
            HttpResponse<String> resp = send(get(r.getKey(), jwt));
            boolean ok = resp.statusCode() == 409 && resp.body().toLowerCase().contains(PHRASE);
            if (!ok) offenders.add(r.getValue() + " -> " + resp.statusCode());
        }
        assertThat(offenders).as("reads that don't tell a dead session to sign in again").isEmpty();
    }

    /**
     * Substitutes the dead user's id into {@code {userId}}. A route with any other path
     * variable is about a record that doesn't exist for this account either, and its
     * 400 or 403 is the right answer, so it is skipped rather than guessed at.
     */
    private String fill(String pattern, HandlerMethod handler, String userId) {
        String path = pattern;
        for (MethodParameter p : handler.getMethodParameters()) {
            PathVariable pv = p.getParameterAnnotation(PathVariable.class);
            if (pv == null) continue;
            String name = pv.value().isEmpty() ? pv.name().isEmpty() ? p.getParameterName() : pv.name() : pv.value();
            if (!"userId".equals(name)) return null;
            path = path.replace("{userId}", userId);
        }
        return path.contains("{") ? null : path;
    }

    /** Values for required query parameters, so a missing one isn't mistaken for a 409. */
    private String queryFor(HandlerMethod handler) {
        StringBuilder q = new StringBuilder();
        for (MethodParameter p : handler.getMethodParameters()) {
            RequestParam rp = p.getParameterAnnotation(RequestParam.class);
            if (rp == null || !rp.required() || !ValueConstants.DEFAULT_NONE.equals(rp.defaultValue())) continue;
            String name = rp.value().isEmpty() ? rp.name().isEmpty() ? p.getParameterName() : rp.name() : rp.value();
            Class<?> type = p.getParameterType();
            String value = type.isEnum() ? type.getEnumConstants()[0].toString()
                    : Number.class.isAssignableFrom(type) || type.isPrimitive() ? "7" : "x";
            q.append(q.length() == 0 ? '?' : '&').append(name).append('=').append(value);
        }
        return q.toString();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String url(String path) { return "http://localhost:" + port + path; }

    private com.fasterxml.jackson.databind.JsonNode json(HttpResponse<String> r) throws Exception {
        return objectMapper.readTree(r.body());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest get(String path, String jwt) {
        return HttpRequest.newBuilder().uri(URI.create(url(path)))
                .header("Authorization", "Bearer " + jwt).GET().build();
    }

    private HttpRequest post(String path, Object body, String jwt) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        if (jwt != null) b.header("Authorization", "Bearer " + jwt);
        return b.build();
    }
}
