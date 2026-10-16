package com.project.fitness.service;

import com.project.fitness.model.*;
import com.project.fitness.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ChallengeService — Feature 14, and group challenges (PLAN P2-4).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChallengeService Tests")
class ChallengeServiceTest {

    @Mock private ChallengeRepository challengeRepository;
    @Mock private ChallengeParticipantRepository participantRepository;
    // Settling a challenge now announces the result to every competitor (PLAN P2-2).
    @Mock private NotificationService notificationService;
    @Mock private ChallengeLeaderboardRepository leaderboardRepository;
    @Mock private UserRepository userRepository;
    @Mock private ActivityRepository activityRepository;
    @Mock private FriendshipService friendshipService;
    @InjectMocks private ChallengeService challengeService;

    private User creator;
    private User opponent;
    private User third;

    @BeforeEach
    void setUp() {
        creator  = User.builder().id("creator-1").firstName("Nikhil").lastName("Mehta").build();
        opponent = User.builder().id("opponent-1").firstName("Rahul").lastName("Sharma").build();
        third    = User.builder().id("third-1").firstName("Priya").lastName("Nair").build();
        // Everyone is friends unless a test says otherwise: challenges are friends-only
        // (PLAN P4-4), and most tests here are about something else.
        lenient().when(friendshipService.areFriends(anyString(), anyString())).thenReturn(true);
    }

    /** A challenge in the new shape: participants, not a creator/opponent pair. */
    private Challenge buildChallenge(ChallengeStatus status, User... invited) {
        Challenge challenge = Challenge.builder()
                .id("challenge-1")
                .creator(creator)
                .type(ChallengeType.CALORIES_BURNED)
                .title("Test Challenge")
                .status(status)
                .startDate(LocalDate.now())
                .endDate(LocalDate.now().plusDays(7))
                .build();

        challenge.addParticipant(ChallengeParticipant.builder()
                .id("p-creator").user(creator).isCreator(true)
                .status(ParticipantStatus.ACCEPTED).score(0.0).build());

        for (User user : invited) {
            challenge.addParticipant(ChallengeParticipant.builder()
                    .id("p-" + user.getId()).user(user).isCreator(false)
                    .status(status == ChallengeStatus.PENDING
                            ? ParticipantStatus.INVITED : ParticipantStatus.ACCEPTED)
                    .score(0.0).build());
        }
        return challenge;
    }

    private void saveEchoes() {
        when(challengeRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    // ── Create ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Creating a challenge")
    class Creating {

        @Test
        @DisplayName("a 1-v-1 challenge is PENDING with both people as participants")
        void oneVsOne() {
            when(userRepository.findById("creator-1")).thenReturn(Optional.of(creator));
            when(userRepository.findById("opponent-1")).thenReturn(Optional.of(opponent));
            when(challengeRepository.findAllByUserId("creator-1")).thenReturn(List.of());
            saveEchoes();

            Map<String, Object> result = challengeService.createChallenge(
                    "creator-1", List.of("opponent-1"), ChallengeType.CALORIES_BURNED, "Week Burn Off", 7);

            assertThat(result.get("status")).isEqualTo("PENDING");
            assertThat(result.get("title")).isEqualTo("Week Burn Off");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> participants = (List<Map<String, Object>>) result.get("participants");
            assertThat(participants).hasSize(2);
            verify(challengeRepository).save(any(Challenge.class));
        }

        @Test
        @DisplayName("a group challenge invites everyone and counts only the creator as in")
        void group() {
            when(userRepository.findById("creator-1")).thenReturn(Optional.of(creator));
            when(userRepository.findById("opponent-1")).thenReturn(Optional.of(opponent));
            when(userRepository.findById("third-1")).thenReturn(Optional.of(third));
            when(challengeRepository.findAllByUserId("creator-1")).thenReturn(List.of());
            saveEchoes();

            Map<String, Object> result = challengeService.createChallenge(
                    "creator-1", List.of("opponent-1", "third-1"),
                    ChallengeType.WORKOUT_COUNT, null, 7);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> participants = (List<Map<String, Object>>) result.get("participants");
            assertThat(participants).hasSize(3);
            // Only the creator has accepted so far. An unanswered invite is not a
            // competitor, so the count is 1 rather than 3.
            assertThat(result.get("participantCount")).isEqualTo(1);
            assertThat(result.get("title")).isEqualTo("Nikhil + 2 others — WORKOUT COUNT");

            // Everyone invited hears about it (PLAN P2-2).
            verify(notificationService, times(2)).notify(any(), eq(NotificationType.GENERAL),
                    anyString(), anyString(), eq("challenges"), anyString());
        }

        @Test
        @DisplayName("inviting the same person twice invites them once")
        void deduplicatesInvitees() {
            when(userRepository.findById("creator-1")).thenReturn(Optional.of(creator));
            when(userRepository.findById("opponent-1")).thenReturn(Optional.of(opponent));
            when(challengeRepository.findAllByUserId("creator-1")).thenReturn(List.of());
            saveEchoes();

            Map<String, Object> result = challengeService.createChallenge(
                    "creator-1", List.of("opponent-1", "opponent-1"),
                    ChallengeType.CALORIES_BURNED, "Dupe", 7);

            // The participant pair is unique in the database — a duplicate would have
            // surfaced as a constraint violation, i.e. a 500, rather than a no-op.
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> participants = (List<Map<String, Object>>) result.get("participants");
            assertThat(participants).hasSize(2);
        }

        @Test
        @DisplayName("a user cannot challenge only themselves")
        void selfChallengeRejected() {
            when(userRepository.findById("creator-1")).thenReturn(Optional.of(creator));

            assertThatThrownBy(() -> challengeService.createChallenge(
                    "creator-1", List.of("creator-1"), ChallengeType.CALORIES_BURNED, "Solo", 7))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("cannot challenge yourself");
        }

        /** No limit used to mean an end date in 2300, or a 409 from a date overflow. */
        @Test
        @DisplayName("a challenge longer than a year is rejected before anything is saved")
        void overlongChallengeRejected() {
            when(userRepository.findById("creator-1")).thenReturn(Optional.of(creator));

            for (int days : new int[] {366, 100_000, 2_000_000_000}) {
                assertThatThrownBy(() -> challengeService.createChallenge(
                        "creator-1", List.of("opponent-1"), ChallengeType.TOTAL_DURATION, "Forever", days))
                        .hasMessageContaining("at most 365 days");
            }
            verify(challengeRepository, never()).save(any());
        }

        @Test
        @DisplayName("a challenge with nobody invited is rejected")
        void emptyInviteListRejected() {
            when(userRepository.findById("creator-1")).thenReturn(Optional.of(creator));

            assertThatThrownBy(() -> challengeService.createChallenge(
                    "creator-1", List.of(), ChallengeType.CALORIES_BURNED, "Nobody", 7))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("at least one other person");
        }

        @Test
        @DisplayName("an existing live challenge with any invitee blocks a second one")
        void duplicateActiveRejected() {
            Challenge active = buildChallenge(ChallengeStatus.ACTIVE, opponent);
            when(userRepository.findById("creator-1")).thenReturn(Optional.of(creator));
            when(userRepository.findById("opponent-1")).thenReturn(Optional.of(opponent));
            when(challengeRepository.findAllByUserId("creator-1")).thenReturn(List.of(active));

            assertThatThrownBy(() -> challengeService.createChallenge(
                    "creator-1", List.of("opponent-1"), ChallengeType.WORKOUT_COUNT, "Another", 7))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("active or pending challenge");
        }
    }

    // ── One open challenge per pair (PLAN P4-15) ─────────────────────────────

    @Nested
    @DisplayName("The one-live-challenge-per-person rule")
    class OneAtATime {

        private Map<String, Object> inviteOpponent() {
            when(userRepository.findById("creator-1")).thenReturn(Optional.of(creator));
            when(userRepository.findById("opponent-1")).thenReturn(Optional.of(opponent));
            saveEchoes();
            return challengeService.createChallenge("creator-1", List.of("opponent-1"),
                    ChallengeType.WORKOUT_COUNT, "Again", 7);
        }

        /**
         * "Involves" counted anyone who was ever on the challenge. Declining and leaving
         * both set DECLINED, so someone who had walked away from a running challenge
         * still blocked the two of them from starting a new one.
         */
        @Test
        @DisplayName("someone who declined or left a running challenge no longer blocks a new one")
        void declinedOrLeftDoesNotBlock() {
            Challenge running = buildChallenge(ChallengeStatus.ACTIVE, opponent, third);
            running.participantFor("opponent-1").orElseThrow().setStatus(ParticipantStatus.DECLINED);
            when(challengeRepository.findAllByUserId("creator-1")).thenReturn(List.of(running));

            assertThat(inviteOpponent().get("status")).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("an invitee who hasn't answered yet still blocks it")
        void unansweredInviteStillBlocks() {
            Challenge pending = buildChallenge(ChallengeStatus.PENDING, opponent);   // opponent is INVITED
            when(userRepository.findById("creator-1")).thenReturn(Optional.of(creator));
            when(userRepository.findById("opponent-1")).thenReturn(Optional.of(opponent));
            when(challengeRepository.findAllByUserId("creator-1")).thenReturn(List.of(pending));

            assertThatThrownBy(() -> challengeService.createChallenge("creator-1", List.of("opponent-1"),
                    ChallengeType.WORKOUT_COUNT, "Again", 7)).hasMessageContaining("active or pending");
        }

        @Test
        @DisplayName("the creator having left their own running challenge doesn't block either")
        void creatorWhoLeftDoesNotBlock() {
            Challenge running = buildChallenge(ChallengeStatus.ACTIVE, opponent);
            running.participantFor("creator-1").orElseThrow().setStatus(ParticipantStatus.DECLINED);
            when(challengeRepository.findAllByUserId("creator-1")).thenReturn(List.of(running));

            assertThat(inviteOpponent().get("status")).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("one still in with the opponent is enough to block, even among several challenges")
        void anyLiveSharedChallengeBlocks() {
            Challenge left = buildChallenge(ChallengeStatus.ACTIVE, opponent);
            left.participantFor("opponent-1").orElseThrow().setStatus(ParticipantStatus.DECLINED);
            Challenge live = buildChallenge(ChallengeStatus.ACTIVE, opponent);
            when(userRepository.findById("creator-1")).thenReturn(Optional.of(creator));
            when(userRepository.findById("opponent-1")).thenReturn(Optional.of(opponent));
            when(challengeRepository.findAllByUserId("creator-1")).thenReturn(List.of(left, live));

            assertThatThrownBy(() -> challengeService.createChallenge("creator-1", List.of("opponent-1"),
                    ChallengeType.WORKOUT_COUNT, "Again", 7)).hasMessageContaining("active or pending");
        }
    }

    // ── Friends only (PLAN P4-4) ─────────────────────────────────────────────

    @Nested
    @DisplayName("Who you can challenge, and whose record you can see")
    class FriendsOnly {

        @Test
        @DisplayName("inviting someone who isn't a friend is refused, and nothing is saved")
        void nonFriendRejected() {
            when(userRepository.findById("creator-1")).thenReturn(Optional.of(creator));
            when(friendshipService.areFriends("creator-1", "opponent-1")).thenReturn(false);

            assertThatThrownBy(() -> challengeService.createChallenge(
                    "creator-1", List.of("opponent-1"), ChallengeType.CALORIES_BURNED, "Hi", 7))
                    .hasMessage(ChallengeService.NOT_FRIENDS);
            verify(challengeRepository, never()).save(any());
        }

        @Test
        @DisplayName("one non-friend in a group sinks the whole invite")
        void oneNonFriendInAGroupRejected() {
            when(userRepository.findById("creator-1")).thenReturn(Optional.of(creator));
            lenient().when(userRepository.findById("opponent-1")).thenReturn(Optional.of(opponent));
            when(friendshipService.areFriends("creator-1", "third-1")).thenReturn(false);

            assertThatThrownBy(() -> challengeService.createChallenge(
                    "creator-1", List.of("opponent-1", "third-1"), ChallengeType.WORKOUT_COUNT, null, 7))
                    .hasMessage(ChallengeService.NOT_FRIENDS);
            verify(challengeRepository, never()).save(any());
        }

        @Test
        @DisplayName("an id that isn't an account gets the same answer as a non-friend")
        void unknownIdIndistinguishable() {
            when(userRepository.findById("creator-1")).thenReturn(Optional.of(creator));
            when(friendshipService.areFriends("creator-1", "ghost")).thenReturn(false);

            assertThatThrownBy(() -> challengeService.createChallenge(
                    "creator-1", List.of("ghost"), ChallengeType.CALORIES_BURNED, "Hi", 7))
                    .hasMessage(ChallengeService.NOT_FRIENDS)
                    .hasMessageNotContaining("ghost");
        }

        @Test
        @DisplayName("h2h with a friend works even before they've competed")
        void h2hWithFriend() {
            when(userRepository.findById("creator-1")).thenReturn(Optional.of(creator));
            when(userRepository.findById("opponent-1")).thenReturn(Optional.of(opponent));
            when(challengeRepository.findH2HHistory("creator-1", "opponent-1")).thenReturn(List.of());

            Map<String, Object> stats = challengeService.getH2HStats("creator-1", "opponent-1");
            assertThat(stats.get("totalChallenges")).isEqualTo(0);
            assertThat(stats.get("userB")).isEqualTo("Rahul Sharma");
        }

        @Test
        @DisplayName("h2h with a past opponent works after unfriending")
        void h2hWithPastOpponent() {
            Challenge past = buildChallenge(ChallengeStatus.COMPLETED, opponent);
            when(userRepository.findById("creator-1")).thenReturn(Optional.of(creator));
            when(userRepository.findById("opponent-1")).thenReturn(Optional.of(opponent));
            when(challengeRepository.findH2HHistory("creator-1", "opponent-1")).thenReturn(List.of(past));
            lenient().when(friendshipService.areFriends("creator-1", "opponent-1")).thenReturn(false);

            assertThat(challengeService.getH2HStats("creator-1", "opponent-1").get("totalChallenges"))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("h2h with a stranger is a 403 that reveals no name")
        void h2hWithStrangerDenied() {
            when(userRepository.findById("creator-1")).thenReturn(Optional.of(creator));
            when(challengeRepository.findH2HHistory("creator-1", "opponent-1")).thenReturn(List.of());
            when(friendshipService.areFriends("creator-1", "opponent-1")).thenReturn(false);

            assertThatThrownBy(() -> challengeService.getH2HStats("creator-1", "opponent-1"))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessage(ChallengeService.H2H_DENIED);
            verify(userRepository, never()).findById("opponent-1");
        }

        @Test
        @DisplayName("h2h with an id that isn't an account is the same 403")
        void h2hWithUnknownIdDenied() {
            when(userRepository.findById("creator-1")).thenReturn(Optional.of(creator));
            when(challengeRepository.findH2HHistory("creator-1", "ghost")).thenReturn(List.of());
            when(friendshipService.areFriends("creator-1", "ghost")).thenReturn(false);

            assertThatThrownBy(() -> challengeService.getH2HStats("creator-1", "ghost"))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessage(ChallengeService.H2H_DENIED);
        }

        @Test
        @DisplayName("h2h against yourself is refused instead of going negative (P4-11)")
        void h2hWithSelfRejected() {
            assertThatThrownBy(() -> challengeService.getH2HStats("creator-1", "creator-1"))
                    .hasMessageContaining("another person");
        }
    }

    // ── Responding ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Responding to an invitation")
    class Responding {

        @Test
        @DisplayName("the first acceptance starts the clock and makes it ACTIVE")
        void firstAcceptanceStartsTheClock() {
            Challenge challenge = buildChallenge(ChallengeStatus.PENDING, opponent);
            when(challengeRepository.findById("challenge-1")).thenReturn(Optional.of(challenge));
            saveEchoes();

            Map<String, Object> result =
                    challengeService.respondToChallenge("challenge-1", "opponent-1", true);

            assertThat(result.get("status")).isEqualTo("ACTIVE");
            assertThat(challenge.getStartDate()).isEqualTo(LocalDate.now());
            assertThat(challenge.getEndDate()).isEqualTo(LocalDate.now().plusDays(7));
        }

        /**
         * A late accepter joins a window already running — and that is fair, because
         * every score is computed over [startDate, endDate] from the activity log
         * rather than from the moment somebody said yes.
         */
        @Test
        @DisplayName("a later acceptance joins the running challenge rather than being refused")
        void lateAcceptanceJoins() {
            Challenge challenge = buildChallenge(ChallengeStatus.PENDING, opponent, third);
            challenge.participantFor("opponent-1").orElseThrow()
                    .setStatus(ParticipantStatus.ACCEPTED);
            challenge.setStatus(ChallengeStatus.ACTIVE);
            when(challengeRepository.findById("challenge-1")).thenReturn(Optional.of(challenge));
            saveEchoes();

            challengeService.respondToChallenge("challenge-1", "third-1", true);

            assertThat(challenge.participantFor("third-1").orElseThrow().getStatus())
                    .isEqualTo(ParticipantStatus.ACCEPTED);
            assertThat(challenge.competitors()).hasSize(3);
        }

        @Test
        @DisplayName("declining marks only that person, leaving the challenge for the rest")
        void decliningIsPerPerson() {
            Challenge challenge = buildChallenge(ChallengeStatus.PENDING, opponent, third);
            when(challengeRepository.findById("challenge-1")).thenReturn(Optional.of(challenge));
            saveEchoes();

            challengeService.respondToChallenge("challenge-1", "opponent-1", false);

            assertThat(challenge.participantFor("opponent-1").orElseThrow().getStatus())
                    .isEqualTo(ParticipantStatus.DECLINED);
            // The third person hasn't answered, so the challenge is still live for them.
            assertThat(challenge.getStatus()).isEqualTo(ChallengeStatus.PENDING);
        }

        @Test
        @DisplayName("the last decline ends the challenge — the creator has nobody to compete with")
        void lastDeclineEndsIt() {
            Challenge challenge = buildChallenge(ChallengeStatus.PENDING, opponent);
            when(challengeRepository.findById("challenge-1")).thenReturn(Optional.of(challenge));
            saveEchoes();

            Map<String, Object> result =
                    challengeService.respondToChallenge("challenge-1", "opponent-1", false);

            assertThat(result.get("status")).isEqualTo("DECLINED");
        }

        @Test
        @DisplayName("somebody who wasn't invited gets a 403, not a 400")
        void uninvitedUserDenied() {
            Challenge challenge = buildChallenge(ChallengeStatus.PENDING, opponent);
            when(challengeRepository.findById("challenge-1")).thenReturn(Optional.of(challenge));

            // 403 rather than 400: a valid token acting on somebody else's data is not
            // a malformed request (AGENT.md §4).
            assertThatThrownBy(() ->
                    challengeService.respondToChallenge("challenge-1", "stranger-1", true))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("the creator can't accept their own challenge")
        void creatorCannotRespond() {
            Challenge challenge = buildChallenge(ChallengeStatus.PENDING, opponent);
            when(challengeRepository.findById("challenge-1")).thenReturn(Optional.of(challenge));

            assertThatThrownBy(() ->
                    challengeService.respondToChallenge("challenge-1", "creator-1", true))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("already in it");
        }

        @Test
        @DisplayName("answering twice is refused")
        void doubleResponseRefused() {
            Challenge challenge = buildChallenge(ChallengeStatus.ACTIVE, opponent);
            when(challengeRepository.findById("challenge-1")).thenReturn(Optional.of(challenge));

            assertThatThrownBy(() ->
                    challengeService.respondToChallenge("challenge-1", "opponent-1", true))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("already responded");
        }
    }

    // ── Cancelling and leaving ───────────────────────────────────────────────

    @Nested
    @DisplayName("Cancelling and leaving")
    class Leaving {

        @Test
        @DisplayName("the creator can cancel while it is still pending")
        void creatorCancels() {
            Challenge challenge = buildChallenge(ChallengeStatus.PENDING, opponent);
            when(challengeRepository.findById("challenge-1")).thenReturn(Optional.of(challenge));
            saveEchoes();

            challengeService.cancelChallenge("challenge-1", "creator-1");

            assertThat(challenge.getStatus()).isEqualTo(ChallengeStatus.CANCELLED);
        }

        @Test
        @DisplayName("nobody else can cancel it")
        void nonCreatorCannotCancel() {
            Challenge challenge = buildChallenge(ChallengeStatus.PENDING, opponent);
            when(challengeRepository.findById("challenge-1")).thenReturn(Optional.of(challenge));

            assertThatThrownBy(() -> challengeService.cancelChallenge("challenge-1", "opponent-1"))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Only the creator");
        }

        @Test
        @DisplayName("a participant can leave a running challenge without ending it for the rest")
        void participantLeaves() {
            Challenge challenge = buildChallenge(ChallengeStatus.ACTIVE, opponent, third);
            when(challengeRepository.findById("challenge-1")).thenReturn(Optional.of(challenge));
            saveEchoes();

            challengeService.leaveChallenge("challenge-1", "third-1");

            assertThat(challenge.getStatus()).isEqualTo(ChallengeStatus.ACTIVE);
            assertThat(challenge.competitors()).hasSize(2);
        }

        @Test
        @DisplayName("the creator cancels rather than leaves")
        void creatorCannotLeave() {
            Challenge challenge = buildChallenge(ChallengeStatus.ACTIVE, opponent);
            when(challengeRepository.findById("challenge-1")).thenReturn(Optional.of(challenge));

            assertThatThrownBy(() -> challengeService.leaveChallenge("challenge-1", "creator-1"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("cancel it instead");
        }

        @Test
        @DisplayName("someone who already declined or left cannot leave again")
        void declinedCannotLeave() {
            Challenge challenge = buildChallenge(ChallengeStatus.ACTIVE, opponent, third);
            challenge.participantFor("third-1").orElseThrow().setStatus(ParticipantStatus.DECLINED);
            when(challengeRepository.findById("challenge-1")).thenReturn(Optional.of(challenge));

            assertThatThrownBy(() -> challengeService.leaveChallenge("challenge-1", "third-1"))
                    .isExactlyInstanceOf(RuntimeException.class)
                    .hasMessageContaining("no longer part");
            verify(challengeRepository, never()).save(any());
        }

        @Test
        @DisplayName("someone still invited declines rather than leaves")
        void invitedCannotLeave() {
            Challenge challenge = buildChallenge(ChallengeStatus.ACTIVE, opponent, third);
            challenge.participantFor("third-1").orElseThrow().setStatus(ParticipantStatus.INVITED);
            when(challengeRepository.findById("challenge-1")).thenReturn(Optional.of(challenge));

            assertThatThrownBy(() -> challengeService.leaveChallenge("challenge-1", "third-1"))
                    .isExactlyInstanceOf(RuntimeException.class)
                    .hasMessageContaining("decline the invite");
            verify(challengeRepository, never()).save(any());
        }
    }

    // ── Settlement ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Settling a finished challenge")
    class Settlement {

        private Challenge expired(User... invited) {
            Challenge challenge = buildChallenge(ChallengeStatus.ACTIVE, invited);
            challenge.setStartDate(LocalDate.now().minusDays(8));
            challenge.setEndDate(LocalDate.now().minusDays(1));
            return challenge;
        }

        private void scores(Challenge challenge, double... byParticipantOrder) {
            List<ChallengeParticipant> participants = challenge.getParticipants();
            for (int i = 0; i < byParticipantOrder.length; i++) {
                participants.get(i).setScore(byParticipantOrder[i]);
            }
        }

        /** Stops updateScores() from zeroing the scores a test just set. */
        private void noActivities() {
            when(activityRepository.findByUserIdAndStartTimeBetween(anyString(), any(), any()))
                    .thenReturn(List.of());
        }

        @Test
        @DisplayName("the highest score wins, and everyone is told")
        void highestScoreWins() {
            Challenge challenge = expired(opponent, third);
            when(challengeRepository.findExpiredActiveChallenges(any())).thenReturn(List.of(challenge));
            noActivities();
            saveEchoes();

            // updateScores runs first and recomputes from an empty activity list, so
            // every score is 0 — which is the draw case, asserted below. The ranking
            // itself is covered by the two tests after this one.
            challengeService.declareExpiredChallengeWinners();

            assertThat(challenge.getStatus()).isEqualTo(ChallengeStatus.COMPLETED);
            assertThat(challenge.getResultMessage()).contains("draw");
            // Three competitors, three notifications.
            verify(notificationService, times(3)).notify(any(), eq(NotificationType.CHALLENGE_RESULT),
                    anyString(), anyString(), eq("challenges"), anyString());
        }

        @Test
        @DisplayName("a group result names the runner-up and the field size")
        void groupResultNamesTheField() {
            Challenge challenge = expired(opponent, third);
            when(challengeRepository.findByStatus(ChallengeStatus.ACTIVE)).thenReturn(List.of(challenge));
            when(activityRepository.findByUserIdAndStartTimeBetween(eq("creator-1"), any(), any()))
                    .thenReturn(List.of(activityBurning(900)));
            when(activityRepository.findByUserIdAndStartTimeBetween(eq("opponent-1"), any(), any()))
                    .thenReturn(List.of(activityBurning(600)));
            when(activityRepository.findByUserIdAndStartTimeBetween(eq("third-1"), any(), any()))
                    .thenReturn(List.of(activityBurning(300)));
            saveEchoes();

            challengeService.updateAllActiveScores();

            assertThat(challenge.participantFor("creator-1").orElseThrow().getScore()).isEqualTo(900.0);
            assertThat(challenge.participantFor("third-1").orElseThrow().getScore()).isEqualTo(300.0);
            // The snapshot carries everyone, not two columns.
            verify(leaderboardRepository).save(any(ChallengeLeaderboardEntry.class));
        }

        @Test
        @DisplayName("an unanswered invite is not scored as a zero")
        void unansweredInviteIsNotScored() {
            Challenge challenge = buildChallenge(ChallengeStatus.ACTIVE, opponent);
            challenge.addParticipant(ChallengeParticipant.builder()
                    .id("p-pending").user(third).isCreator(false)
                    .status(ParticipantStatus.INVITED).score(0.0).build());
            when(challengeRepository.findByStatus(ChallengeStatus.ACTIVE)).thenReturn(List.of(challenge));
            when(activityRepository.findByUserIdAndStartTimeBetween(anyString(), any(), any()))
                    .thenReturn(List.of(activityBurning(500)));
            saveEchoes();

            challengeService.updateAllActiveScores();

            // Ranking them last would report a result about somebody who never took part.
            assertThat(challenge.competitors()).hasSize(2);
            assertThat(challenge.participantFor("third-1").orElseThrow().getScore()).isZero();
        }

        @Test
        @DisplayName("a challenge nobody joined is cancelled, not won by default")
        void nobodyJoinedIsCancelled() {
            Challenge challenge = expired(opponent);
            challenge.participantFor("opponent-1").orElseThrow()
                    .setStatus(ParticipantStatus.DECLINED);
            when(challengeRepository.findExpiredActiveChallenges(any())).thenReturn(List.of(challenge));
            noActivities();
            saveEchoes();

            challengeService.declareExpiredChallengeWinners();

            // Declaring the creator the winner of a competition nobody entered would be
            // inventing a result.
            assertThat(challenge.getStatus()).isEqualTo(ChallengeStatus.CANCELLED);
            assertThat(challenge.getWinner()).isNull();
            assertThat(challenge.getResultMessage()).contains("no contest");
        }

        /**
         * The no-contest path settled a challenge as CANCELLED, then announced it with
         * the same title as a tie, because "no winner" was read as "draw" (PLAN P4-14).
         */
        @Test
        @DisplayName("a cancelled challenge is announced as cancelled, not as a draw")
        void cancelledIsNotAnnouncedAsADraw() {
            Challenge challenge = expired(opponent);
            challenge.participantFor("opponent-1").orElseThrow()
                    .setStatus(ParticipantStatus.DECLINED);
            when(challengeRepository.findExpiredActiveChallenges(any())).thenReturn(List.of(challenge));
            noActivities();
            saveEchoes();

            challengeService.declareExpiredChallengeWinners();

            org.mockito.ArgumentCaptor<String> title = org.mockito.ArgumentCaptor.forClass(String.class);
            org.mockito.ArgumentCaptor<String> body = org.mockito.ArgumentCaptor.forClass(String.class);
            // Only the creator is still competing, so only the creator is told.
            verify(notificationService, times(1)).notify(any(User.class), eq(NotificationType.CHALLENGE_RESULT),
                    title.capture(), body.capture(), eq("challenges"), anyString());
            assertThat(title.getValue()).doesNotContainIgnoringCase("draw").containsIgnoringCase("cancel");
            assertThat(body.getValue()).contains("Nobody else joined");
        }

        /**
         * The challenge map only carried resultMessage for COMPLETED, so the one line that
         * says why a cancelled challenge ended never reached the client (PLAN P4-14).
         */
        @Test
        @DisplayName("a challenge cancelled with no contest still carries its message, but names no winner")
        void cancelledChallengeCarriesItsMessage() {
            Challenge challenge = expired(opponent);
            challenge.participantFor("opponent-1").orElseThrow().setStatus(ParticipantStatus.DECLINED);
            when(challengeRepository.findExpiredActiveChallenges(any())).thenReturn(List.of(challenge));
            noActivities();
            saveEchoes();
            challengeService.declareExpiredChallengeWinners();
            when(challengeRepository.findById("challenge-1")).thenReturn(Optional.of(challenge));

            Map<String, Object> view = challengeService.getChallengeDetail("challenge-1", "creator-1");

            assertThat(view.get("status")).isEqualTo("CANCELLED");
            assertThat(view.get("resultMessage")).isEqualTo("Nobody else joined, so there was no contest.");
            assertThat(view).doesNotContainKey("winner");   // there isn't one, and "Draw" would be wrong
        }

        @Test
        @DisplayName("a challenge its creator cancelled has no message to show")
        void creatorCancelledHasNoMessage() {
            Challenge challenge = buildChallenge(ChallengeStatus.CANCELLED, opponent);
            when(challengeRepository.findById("challenge-1")).thenReturn(Optional.of(challenge));

            Map<String, Object> view = challengeService.getChallengeDetail("challenge-1", "creator-1");

            assertThat(view).doesNotContainKey("resultMessage");
        }

        private Activity activityBurning(int calories) {
            Activity a = new Activity();
            a.setCaloriesBurned(calories);
            a.setStartTime(LocalDate.now().minusDays(2).atTime(9, 0));
            return a;
        }
    }
}
