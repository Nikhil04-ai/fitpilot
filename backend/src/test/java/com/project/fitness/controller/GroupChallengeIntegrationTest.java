package com.project.fitness.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.fitness.dto.ActivityRequest;
import com.project.fitness.dto.LoginRequest;
import com.project.fitness.dto.RegisterRequest;
import com.project.fitness.model.ActivityType;
import com.project.fitness.model.ChallengeType;
import com.project.fitness.service.ChallengeService;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Friends and group challenges end to end (PLAN P2-4).
 *
 * <p>Plain {@code java.net.http.HttpClient}, not TestRestTemplate or MockMvc (AGENT.md §7).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Group Challenge Integration Tests")
class GroupChallengeIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ChallengeService challengeService;

    private final HttpClient http = HttpClient.newHttpClient();

    private String aliceJwt, bobJwt, carolJwt;
    private String aliceId, bobId, carolId;
    private String friendshipId;
    private String challengeId;

    @BeforeAll
    void setupOnce() throws Exception {
        aliceId = register("groupalice@test.com", "Alice", "Adams");
        bobId   = register("groupbob@test.com", "Bob", "Brown");
        carolId = register("groupcarol@test.com", "Carol", "Clark");

        aliceJwt = login("groupalice@test.com");
        bobJwt   = login("groupbob@test.com");
        carolJwt = login("groupcarol@test.com");
    }

    // ── Friends ──────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("A friend request answers identically for a known and an unknown address")
    void friendRequestDoesNotLeakMembership() throws Exception {
        String known = json(send(postAuth(aliceJwt, "/api/friends/request",
                Map.of("email", "groupbob@test.com")))).get("message").asText();
        String unknown = json(send(postAuth(aliceJwt, "/api/friends/request",
                Map.of("email", "nobody-here@test.com")))).get("message").asText();

        // Otherwise this endpoint is a free "does this person have an account here"
        // lookup for anyone with a list of addresses.
        assertThat(unknown).isEqualTo(known);
    }

    @Test
    @Order(2)
    @DisplayName("The request shows up as an incoming invite for the other person only")
    void requestIsIncomingForTheAddressee() throws Exception {
        JsonNode bobsInvites = json(send(getAuth(bobJwt, "/api/friends/invites")));
        assertThat(bobsInvites).hasSize(1);
        assertThat(bobsInvites.get(0).get("name").asText()).isEqualTo("Alice Adams");
        assertThat(bobsInvites.get(0).get("isIncoming").asBoolean()).isTrue();
        friendshipId = bobsInvites.get(0).get("friendshipId").asText();

        // Alice sees it as sent, not as something she can accept.
        assertThat(json(send(getAuth(aliceJwt, "/api/friends/invites")))).isEmpty();
        assertThat(json(send(getAuth(aliceJwt, "/api/friends/sent")))).hasSize(1);
    }

    @Test
    @Order(3)
    @DisplayName("The requester cannot accept on the addressee's behalf")
    void requesterCannotAcceptOwnRequest() throws Exception {
        HttpResponse<String> response =
                send(postAuth(aliceJwt, "/api/friends/" + friendshipId + "/accept", null));

        // Without this, anyone could add themselves to anyone's friends list.
        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    @Order(4)
    @DisplayName("Accepting puts each of them in the other's friends list, from one row")
    void acceptingIsSymmetric() throws Exception {
        assertThat(send(postAuth(bobJwt, "/api/friends/" + friendshipId + "/accept", null))
                .statusCode()).isEqualTo(200);

        JsonNode aliceFriends = json(send(getAuth(aliceJwt, "/api/friends")));
        JsonNode bobFriends = json(send(getAuth(bobJwt, "/api/friends")));

        // One row per pair — the direction is an implementation detail both lists see past.
        assertThat(aliceFriends).hasSize(1);
        assertThat(aliceFriends.get(0).get("name").asText()).isEqualTo("Bob Brown");
        assertThat(bobFriends).hasSize(1);
        assertThat(bobFriends.get(0).get("name").asText()).isEqualTo("Alice Adams");

        // And neither list hands out the other's email address.
        assertThat(aliceFriends.get(0).has("email")).isFalse();
    }

    // ── Group challenges ─────────────────────────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("A challenge can be created for three people at once")
    void createsAThreeWayChallenge() throws Exception {
        // Bob became Alice's friend above; challenges are friends-only (PLAN P4-4).
        befriend(aliceJwt, aliceId, "groupcarol@test.com", carolJwt);

        HttpResponse<String> response = send(postAuth(aliceJwt, "/api/challenges", Map.of(
                "opponentIds", List.of(bobId, carolId),
                "type", ChallengeType.CALORIES_BURNED.name(),
                "title", "Group Burn",
                "durationDays", 7)));
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode challenge = json(response);
        challengeId = challenge.get("id").asText();

        assertThat(challenge.get("status").asText()).isEqualTo("PENDING");
        assertThat(challenge.get("participants")).hasSize(3);
        // Only Alice has accepted so far: an unanswered invite is not a competitor.
        assertThat(challenge.get("participantCount").asInt()).isEqualTo(1);
        assertThat(challenge.get("youAreCreator").asBoolean()).isTrue();
    }

    @Test
    @Order(6)
    @DisplayName("A pre-groups client sending a single opponentId still works")
    void backwardsCompatibleSingleOpponent() throws Exception {
        String dave = register("groupdave@test.com", "Dave", "Davies");
        String daveJwt = login("groupdave@test.com");
        befriend(daveJwt, dave, "groupcarol@test.com", carolJwt);

        HttpResponse<String> response = send(postAuth(daveJwt, "/api/challenges", Map.of(
                "opponentId", carolId,
                "type", ChallengeType.WORKOUT_COUNT.name(),
                "durationDays", 5)));

        // The frontend and every saved API example still send opponentId; dropping it
        // would have broken every existing 1-v-1 call for no gain.
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).get("participants")).hasSize(2);
        assertThat(json(response).get("title").asText()).isEqualTo("Dave vs Carol — WORKOUT COUNT");
    }

    @Test
    @Order(7)
    @DisplayName("The first acceptance starts the clock; a later one joins the running challenge")
    void acceptancesStartAndJoin() throws Exception {
        assertThat(send(postAuth(bobJwt, "/api/challenges/" + challengeId + "/respond",
                Map.of("accept", true))).statusCode()).isEqualTo(200);

        JsonNode afterBob = json(send(getAuth(aliceJwt, "/api/challenges/" + challengeId)));
        assertThat(afterBob.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(afterBob.get("participantCount").asInt()).isEqualTo(2);

        // Carol joins a window already running. Scores are computed over
        // [start, end] from the activity log, so she gains and loses nothing by it.
        assertThat(send(postAuth(carolJwt, "/api/challenges/" + challengeId + "/respond",
                Map.of("accept", true))).statusCode()).isEqualTo(200);

        JsonNode afterCarol = json(send(getAuth(aliceJwt, "/api/challenges/" + challengeId)));
        assertThat(afterCarol.get("participantCount").asInt()).isEqualTo(3);
    }

    @Test
    @Order(8)
    @DisplayName("Standings rank all three by score, live")
    void standingsRankEveryone() throws Exception {
        logWorkout(aliceJwt, 900);
        logWorkout(bobJwt, 600);
        logWorkout(carolJwt, 300);

        JsonNode challenge = json(send(getAuth(bobJwt, "/api/challenges/" + challengeId)));
        JsonNode participants = challenge.get("participants");

        assertThat(participants.get(0).get("name").asText()).isEqualTo("Alice Adams");
        assertThat(participants.get(0).get("score").asDouble()).isEqualTo(900.0);
        assertThat(participants.get(0).get("position").asInt()).isEqualTo(1);
        assertThat(participants.get(2).get("name").asText()).isEqualTo("Carol Clark");
        assertThat(participants.get(2).get("position").asInt()).isEqualTo(3);

        // Bob is looking, so the response is told from his point of view.
        assertThat(challenge.get("yourScore").asDouble()).isEqualTo(600.0);
        assertThat(challenge.get("yourPosition").asInt()).isEqualTo(2);
        assertThat(challenge.get("currentLeader").asText()).isEqualTo("Alice");
    }

    @Test
    @Order(9)
    @DisplayName("Someone can leave a running challenge without ending it for the rest")
    void leavingDoesNotEndIt() throws Exception {
        assertThat(send(postAuth(carolJwt, "/api/challenges/" + challengeId + "/leave", null))
                .statusCode()).isEqualTo(200);

        JsonNode challenge = json(send(getAuth(aliceJwt, "/api/challenges/" + challengeId)));
        assertThat(challenge.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(challenge.get("participantCount").asInt()).isEqualTo(2);
    }

    @Test
    @Order(10)
    @DisplayName("Settling names the winner and tells every competitor")
    void settlementRanksAndAnnounces() throws Exception {
        // Nudge the window into the past, the way a week passing would, so the
        // settlement job picks it up.
        challengeRepository.findById(challengeId).ifPresent(c -> {
            c.setStartDate(java.time.LocalDate.now().minusDays(8));
            c.setEndDate(java.time.LocalDate.now().minusDays(1));
            challengeRepository.save(c);
        });

        // Workouts inside that window. The live scores asserted above were earned
        // today, which is now after the end date — and settlement recomputes from the
        // activity log over [start, end] rather than trusting the stored figures,
        // which is exactly the behaviour that makes a backdated edit correct itself.
        logWorkoutAt(aliceJwt, 900, LocalDateTime.now().minusDays(3));
        logWorkoutAt(bobJwt, 600, LocalDateTime.now().minusDays(3));

        challengeService.declareExpiredChallengeWinners();

        JsonNode challenge = json(send(getAuth(aliceJwt, "/api/challenges/" + challengeId)));
        assertThat(challenge.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(challenge.get("winner").asText()).isEqualTo("Alice Adams");

        // Both remaining competitors were told (PLAN P2-2).
        assertThat(unreadCount(aliceJwt)).isPositive();
        assertThat(unreadCount(bobJwt)).isPositive();
    }

    @Test
    @Order(11)
    @DisplayName("A challenge is private to the people in it")
    void nonParticipantDenied() throws Exception {
        String eve = register("groupeve@test.com", "Eve", "Evans");
        String eveJwt = login("groupeve@test.com");
        assertThat(eve).isNotBlank();

        HttpResponse<String> response = send(getAuth(eveJwt, "/api/challenges/" + challengeId));
        assertThat(response.statusCode()).isEqualTo(403);

        // And she cannot answer an invitation she never received.
        assertThat(send(postAuth(eveJwt, "/api/challenges/" + challengeId + "/respond",
                Map.of("accept", true))).statusCode()).isEqualTo(403);
    }

    @Test
    @Order(12)
    @DisplayName("Only friends can be challenged, and an unknown id gets the same answer (P4-4)")
    void challengesAreFriendsOnly() throws Exception {
        String frank = register("groupfrank@test.com", "Frank", "Foster");
        String frankJwt = login("groupfrank@test.com");

        HttpResponse<String> stranger = send(postAuth(frankJwt, "/api/challenges", Map.of(
                "opponentIds", List.of(aliceId), "type", "WORKOUT_COUNT", "durationDays", 7)));
        HttpResponse<String> nobody = send(postAuth(frankJwt, "/api/challenges", Map.of(
                "opponentIds", List.of("no-such-user-id"), "type", "WORKOUT_COUNT", "durationDays", 7)));

        assertThat(stranger.statusCode()).isEqualTo(400);
        assertThat(json(stranger).get("error").asText()).isEqualTo("You can only challenge your friends.");
        // "Opponent not found: <id>" used to tell real ids from made-up ones.
        assertThat(nobody.statusCode()).isEqualTo(400);
        assertThat(nobody.body()).isEqualTo(stranger.body());
        assertThat(frank).isNotBlank();
    }

    @Test
    @Order(13)
    @DisplayName("Head-to-head is for friends and past opponents; a stranger's name stays hidden (P4-4)")
    void h2hIsNotANameLookup() throws Exception {
        String grace = register("groupgrace@test.com", "Grace", "Green");
        String graceJwt = login("groupgrace@test.com");

        // Alice and Bob are friends (and competed above): allowed.
        HttpResponse<String> friends = send(getAuth(aliceJwt, "/api/challenges/h2h/" + aliceId + "/" + bobId));
        assertThat(friends.statusCode()).isEqualTo(200);
        assertThat(json(friends).get("userB").asText()).isEqualTo("Bob Brown");

        // A stranger used to get Alice's full name back from her id alone.
        HttpResponse<String> stranger = send(getAuth(graceJwt, "/api/challenges/h2h/" + grace + "/" + aliceId));
        HttpResponse<String> nobody = send(getAuth(graceJwt, "/api/challenges/h2h/" + grace + "/no-such-user-id"));
        assertThat(stranger.statusCode()).isEqualTo(403);
        assertThat(stranger.body()).doesNotContain("Alice").doesNotContain("Adams");
        assertThat(nobody.statusCode()).isEqualTo(403);
        assertThat(nobody.body()).isEqualTo(stranger.body());

        // Against yourself: every challenge involves you, and the counts went negative.
        assertThat(send(getAuth(graceJwt, "/api/challenges/h2h/" + grace + "/" + grace)).statusCode())
                .isEqualTo(400);
    }

    @Test
    @Order(14)
    @DisplayName("Answering an invite without saying yes or no is refused, not read as a decline (P4-13)")
    void respondNeedsAnExplicitAnswer() throws Exception {
        String heidi = register("grouphidei@test.com", "Heidi", "Hall");
        String ivan = register("groupivan@test.com", "Ivan", "Ives");
        String heidiJwt = login("grouphidei@test.com");
        String ivanJwt = login("groupivan@test.com");
        befriend(heidiJwt, heidi, "groupivan@test.com", ivanJwt);

        String id = json(send(postAuth(heidiJwt, "/api/challenges", Map.of(
                "opponentIds", List.of(ivan), "type", "WORKOUT_COUNT", "durationDays", 7)))).get("id").asText();

        // The primitive boolean made each of these a decline.
        for (Object body : new Object[] {Map.of(), Map.of("userId", ivan), new java.util.HashMap<String, Object>() {{ put("accept", null); }}}) {
            HttpResponse<String> response = send(postAuth(ivanJwt, "/api/challenges/" + id + "/respond", body));
            assertThat(response.statusCode()).as(body.toString()).isEqualTo(400);
            assertThat(json(response).get("error").asText()).contains("accept");
        }

        // None of them spent the invite: it is still waiting for an answer.
        assertThat(json(send(getAuth(ivanJwt, "/api/challenges/invites/" + ivan)))).hasSize(1);

        // An explicit answer, either way, still works.
        assertThat(send(postAuth(ivanJwt, "/api/challenges/" + id + "/respond",
                Map.of("accept", true))).statusCode()).isEqualTo(200);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Sends a friend request and has the addressee accept it. */
    private void befriend(String requesterJwt, String requesterId,
                          String addresseeEmail, String addresseeJwt) throws Exception {
        assertThat(send(postAuth(requesterJwt, "/api/friends/request",
                Map.of("email", addresseeEmail))).statusCode()).isEqualTo(200);
        String friendship = null;
        for (JsonNode invite : json(send(getAuth(addresseeJwt, "/api/friends/invites")))) {
            if (requesterId.equals(invite.get("userId").asText())) {
                friendship = invite.get("friendshipId").asText();
            }
        }
        assertThat(friendship).as("pending request from %s", requesterId).isNotNull();
        assertThat(send(postAuth(addresseeJwt, "/api/friends/" + friendship + "/accept", null))
                .statusCode()).isEqualTo(200);
    }

    @Autowired private com.project.fitness.repository.ChallengeRepository challengeRepository;

    private int unreadCount(String jwt) throws Exception {
        return json(send(getAuth(jwt, "/api/notifications/unread-count"))).get("unread").asInt();
    }

    private void logWorkout(String jwt, int calories) throws Exception {
        logWorkoutAt(jwt, calories, LocalDateTime.now());
    }

    private void logWorkoutAt(String jwt, int calories, LocalDateTime when) throws Exception {
        ActivityRequest workout = new ActivityRequest();
        workout.setType(ActivityType.RUNNING);
        workout.setDuration(40);
        workout.setCaloriesBurned(calories);
        workout.setStartTime(when);
        assertThat(send(postAuth(jwt, "/api/activities", workout)).statusCode()).isEqualTo(200);
    }

    private String register(String email, String first, String last) throws Exception {
        RegisterRequest reg = new RegisterRequest();
        reg.setEmail(email);
        reg.setPassword("testPass123");
        reg.setFirstName(first);
        reg.setLastName(last);
        reg.setWeight(75.0);
        reg.setHeight(178.0);
        reg.setAge(29);
        HttpResponse<String> response = send(post("/api/auth/register", reg));
        assertThat(response.statusCode()).isEqualTo(200);
        return json(response).get("id").asText();
    }

    private String login(String email) throws Exception {
        LoginRequest login = new LoginRequest();
        login.setEmail(email);
        login.setPassword("testPass123");
        return json(send(post("/api/auth/login", login))).get("accessToken").asText();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return objectMapper.readTree(response.body());
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

    private HttpRequest postAuth(String jwt, String path, Object body) throws Exception {
        return HttpRequest.newBuilder()
                .uri(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + jwt)
                .POST(body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
    }

    private HttpRequest getAuth(String jwt, String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url(path)))
                .header("Authorization", "Bearer " + jwt)
                .GET().build();
    }
}
