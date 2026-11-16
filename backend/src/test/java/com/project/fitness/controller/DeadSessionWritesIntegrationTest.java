package com.project.fitness.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — every write must tell a dead session to sign in again (PLAN P4-18).
 *
 * <p>{@code DeadSessionReadsIntegrationTest} did this for every {@code GET} and found 21
 * routes that answered 200 to an account that no longer existed. Writes were left as "not
 * covered: most already look the user up", and only {@code POST /api/activities} was ever
 * tried. This is the same check for the rest.
 *
 * <p>Routes are discovered from Spring's handler mapping, not listed. A write needs a
 * request body, though, so each discovered route has to be <em>classified</em>:
 * <ul>
 *   <li><b>BODY</b>: sent a valid body. A dead session must get the 409 the frontend
 *       recovers from, the same one the reads give.</li>
 *   <li><b>RECORD</b>: about a record named in the path (an activity, a goal, a friendship).
 *       After a database reset that record does not exist either, so an ordinary "not
 *       found" is the right answer and a 409 is not demanded. It must still never succeed
 *       and never be a 500.</li>
 *   <li><b>AI</b>: needs Gemini. With no key the route refuses before it ever looks the user
 *       up, so only "never succeeds, never a 500" can be asserted here.</li>
 * </ul>
 * A new write route that is in none of them fails the test until someone says what it
 * should send, so it can't go unchecked the way the reads did. {@code /api/auth/**} is
 * exempt: those routes are how a session starts and ends.
 *
 * <p>Per AGENT.md §7, plain java.net.http.HttpClient — not TestRestTemplate.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Dead session — every write")
class DeadSessionWritesIntegrationTest {

    private static final String PHRASE = "account that no longer exists";
    private static final String PASSWORD = "testPass123";
    private static final String NEW_PASSWORD = "newPass12345";
    private static final String NO_SUCH_ID = "00000000-0000-0000-0000-000000000000";
    private static final Set<RequestMethod> WRITES =
            EnumSet.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH);
    private static final Pattern PATH_VAR = Pattern.compile("\\{(\\w+)}");

    /** A BODY route's request body, and whether a live account must get a 2xx from it. */
    private record Write(Supplier<Object> body, boolean liveMustSucceed) {}

    @LocalServerPort private int port;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    // Named, because the actuator registers a second mapping of the same type.
    @Autowired @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private final HttpClient http = HttpClient.newHttpClient();
    private String currentPassword = PASSWORD;

    /** Routes that are sent a body. Keyed "METHOD pattern", in the order they are run. */
    private Map<String, Write> bodyRoutes() {
        Map<String, Write> m = new LinkedHashMap<>();
        m.put("POST /api/activities", new Write(() -> Map.of("type", "RUNNING", "duration", 30), true));
        m.put("POST /api/goals", new Write(() -> Map.of("type", "WORKOUTS_PER_WEEK", "targetValue", 3), true));
        m.put("PUT /api/health/metrics/{userId}", new Write(() -> Map.of("weight", 70.0), true));
        m.put("POST /api/health/sleep/{userId}", new Write(() -> Map.of(
                "sleepDate", LocalDate.now().minusDays(1).toString(), "hoursSlept", 7.0, "qualityRating", 3), true));
        m.put("POST /api/health/water/{userId}", new Write(() -> Map.of("amountMl", 250.0), true));
        m.put("POST /api/nutrition/meals", new Write(() -> Map.of("calories", 500), true));
        m.put("POST /api/pain-log", new Write(() -> Map.of("bodyPart", "LEFT_KNEE", "severity", 3), true));
        m.put("PUT /api/profile/{userId}", new Write(() -> Map.of(
                "firstName", "Dead", "lastName", "Writes", "weight", 70.0, "height", 175.0, "age", 30), true));
        m.put("POST /api/notifications/read-all", new Write(() -> null, true));
        m.put("POST /api/plans/{planId}/enroll", new Write(() -> null, true));
        // These can't succeed for a brand-new account (no friends, nothing logged, nothing
        // to complete), so a live account only has to not claim it is gone.
        m.put("POST /api/challenges", new Write(() -> Map.of(
                "opponentId", "no-such-user-id", "type", "WORKOUT_COUNT", "durationDays", 7), false));
        m.put("POST /api/friends/request", new Write(() -> Map.of("email", "nobody@example.com"), false));
        m.put("POST /api/digest/generate", new Write(() -> null, false));
        m.put("POST /api/plans/active/{userId}/complete-day", new Write(() -> null, false));
        m.put("POST /api/recommendation/generate", new Write(() -> Map.of("activityId", NO_SUCH_ID), false));
        // Last: it retires every token issued before it, so the session is renewed after.
        m.put("PUT /api/profile/{userId}/password", new Write(() ->
                Map.of("currentPassword", currentPassword, "newPassword", NEW_PASSWORD), true));
        return m;
    }

    /** Routes that call Gemini; with no key in the test profile they refuse before any lookup. */
    private static final Set<String> AI_ROUTES = Set.of(
            "POST /api/activities/natural", "POST /api/nutrition/meals/natural");

    @Test
    @DisplayName("every write route that depends on the caller returns the 409 for a deleted account")
    void everyWriteReturnsTheDeadSession409() throws Exception {
        String email = "deadwrites@test.com";
        String userId = json(send(request("POST", "/api/auth/register", Map.of(
                "email", email, "password", PASSWORD, "firstName", "Dead", "lastName", "Writes",
                "weight", 70.0, "height", 175.0, "age", 30), null))).get("id").asText();
        String jwt = login(email, PASSWORD);
        // A seeded system plan to enroll in. (This used to be read from the table, because
        // GET /api/plans was a 500 under this profile, where open-in-view is off: PLAN O-9.)
        String planId = json(send(request("GET", "/api/plans", null, jwt))).get(0).get("id").asText();

        // ── Discover and classify every write route ─────────────────────────────
        Map<String, Write> body = bodyRoutes();
        Map<String, String> bodyPaths = new LinkedHashMap<>();    // key -> path to call
        Map<String, String> recordPaths = new LinkedHashMap<>();
        Map<String, String> aiPaths = new LinkedHashMap<>();
        List<String> unclassified = new ArrayList<>();
        Set<String> discovered = new TreeSet<>();

        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = e.getKey();
            for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                if (!WRITES.contains(method)) continue;
                for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                    if (!pattern.startsWith("/api/") || pattern.startsWith("/api/auth/")
                            || pattern.startsWith("/api/admin/")) continue;
                    String key = method + " " + pattern;
                    discovered.add(key);

                    Set<String> vars = new LinkedHashSet<>();
                    Matcher mt = PATH_VAR.matcher(pattern);
                    while (mt.find()) vars.add(mt.group(1));
                    Set<String> recordVars = new LinkedHashSet<>(vars);
                    recordVars.removeAll(Set.of("userId", "planId"));

                    if (!recordVars.isEmpty()) {
                        recordPaths.put(key, fill(pattern, userId, planId, true));
                    } else if (AI_ROUTES.contains(key)) {
                        aiPaths.put(key, pattern);
                    } else if (body.containsKey(key)) {
                        bodyPaths.put(key, fill(pattern, userId, planId, false));
                    } else {
                        unclassified.add(key);
                    }
                }
            }
        }

        // A floor, so the discovery can't quietly find nothing and pass.
        assertThat(discovered).as("write routes discovered").hasSizeGreaterThan(25);
        assertThat(unclassified).as("write routes with no entry here: say what each should be sent, "
                + "in bodyRoutes() (or AI_ROUTES), so it is checked").isEmpty();
        // And a floor on each class, so a mistake in the classification can't empty one of
        // the three checks below without anyone noticing.
        assertThat(bodyPaths).as("BODY routes").hasSize(body.size());
        assertThat(recordPaths).as("RECORD routes").hasSizeGreaterThan(10);
        assertThat(aiPaths).as("AI routes").hasSize(AI_ROUTES.size());
        Set<String> stale = new TreeSet<>(body.keySet());
        stale.addAll(AI_ROUTES);
        stale.removeAll(discovered);
        assertThat(stale).as("entries here for routes that no longer exist").isEmpty();

        // ── Control: a live account must never be told it is gone ───────────────
        List<String> liveProblems = new ArrayList<>();
        for (Map.Entry<String, String> r : bodyPaths.entrySet()) {
            Write w = body.get(r.getKey());
            check(liveProblems, r.getKey(), send(request(verb(r.getKey()), r.getValue(), w.body().get(), jwt)),
                    w.liveMustSucceed());
            if (r.getKey().equals("PUT /api/profile/{userId}/password")) {
                currentPassword = NEW_PASSWORD;
                jwt = login(email, NEW_PASSWORD);   // the change retired the old token
            }
        }
        for (Map.Entry<String, String> r : recordPaths.entrySet()) {
            check(liveProblems, r.getKey(), send(request(verb(r.getKey()), r.getValue(), Map.of(), jwt)), false);
        }
        for (Map.Entry<String, String> r : aiPaths.entrySet()) {
            check(liveProblems, r.getKey(), send(request("POST", r.getValue(),
                    Map.of("description", "ran 5 km in 30 minutes"), jwt)), false);
        }
        assertThat(liveProblems)
                .as("with the account present: no 409 and no 500, and the bodies here must be valid (2xx where marked)")
                .isEmpty();

        // ── The account vanishes while the token stays valid, as after a database reset ──
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        jdbc.update("DELETE FROM fitness_user WHERE id = ?", userId);
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");

        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, String> r : bodyPaths.entrySet()) {
            HttpResponse<String> resp = send(request(verb(r.getKey()), r.getValue(), body.get(r.getKey()).body().get(), jwt));
            boolean ok = resp.statusCode() == 409 && resp.body().toLowerCase().contains(PHRASE);
            if (!ok) offenders.add(r.getKey() + " -> " + resp.statusCode() + " " + snippet(resp));
        }
        assertThat(offenders).as("writes that don't tell a dead session to sign in again").isEmpty();

        List<String> bad = new ArrayList<>();
        for (Map.Entry<String, String> r : recordPaths.entrySet()) {
            HttpResponse<String> resp = send(request(verb(r.getKey()), r.getValue(), Map.of(), jwt));
            if (resp.statusCode() < 300 || resp.statusCode() >= 500) {
                bad.add(r.getKey() + " -> " + resp.statusCode() + " " + snippet(resp));
            }
        }
        for (Map.Entry<String, String> r : aiPaths.entrySet()) {
            HttpResponse<String> resp = send(request("POST", r.getValue(),
                    Map.of("description", "ran 5 km in 30 minutes"), jwt));
            if (resp.statusCode() < 300 || resp.statusCode() >= 500) {
                bad.add(r.getKey() + " -> " + resp.statusCode() + " " + snippet(resp));
            }
        }
        assertThat(bad).as("record and AI writes from a dead session must be refused, never a success or a 500")
                .isEmpty();
    }

    /** Records into {@code problems} any live-account response that breaks the control's rules. */
    private void check(List<String> problems, String key, HttpResponse<String> resp, boolean mustSucceed) {
        int s = resp.statusCode();
        if (s == 409) problems.add(key + " -> 409 for a live account: " + snippet(resp));
        else if (s >= 500) problems.add(key + " -> " + s + " " + snippet(resp));
        else if (mustSucceed && (s < 200 || s >= 300)) problems.add(key + " -> " + s + " (body not valid?) " + snippet(resp));
    }

    /** {userId} and {planId} get real values; with {@code record}, any other variable gets an id that doesn't exist. */
    private String fill(String pattern, String userId, String planId, boolean record) {
        String path = pattern.replace("{userId}", userId).replace("{planId}", planId);
        return record ? PATH_VAR.matcher(path).replaceAll(NO_SUCH_ID) : path;
    }

    private static String verb(String key) { return key.substring(0, key.indexOf(' ')); }

    private static String snippet(HttpResponse<String> r) {
        String b = r.body() == null ? "" : r.body().replaceAll("\\s+", " ");
        return b.length() > 110 ? b.substring(0, 110) + "…" : b;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String login(String email, String password) throws Exception {
        return json(send(request("POST", "/api/auth/login",
                Map.of("email", email, "password", password), null))).get("accessToken").asText();
    }

    private String url(String path) { return "http://localhost:" + port + path; }

    private JsonNode json(HttpResponse<String> r) throws Exception { return objectMapper.readTree(r.body()); }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** A request with a JSON body when there is one, and no body at all when {@code body} is null. */
    private HttpRequest request(String method, String path, Object body, String jwt) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(url(path)));
        if (body != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        if (jwt != null) b.header("Authorization", "Bearer " + jwt);
        return b.build();
    }
}
