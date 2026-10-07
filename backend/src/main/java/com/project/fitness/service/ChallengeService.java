package com.project.fitness.service;

import com.project.fitness.exceptions.AccountNotFoundException;
import com.project.fitness.model.*;
import com.project.fitness.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Feature 14 — Workout Buddy / Challenge System
 *
 * <p>Full lifecycle: create → invites go out → people accept or decline → active
 * competition → nightly score updates → winner declared at the end date.
 *
 * <p>Types: CALORIES_BURNED, WORKOUT_COUNT, TOTAL_DURATION, STREAK_LENGTH.
 *
 * <p><b>Group challenges (PLAN P2-4).</b> This was 1-v-1 in the table, not just in the
 * UI: a creator and an opponent field, a creatorScore and an opponentScore column.
 * {@link ChallengeParticipant} replaces both pairs, so the same code path runs for two
 * people and for eight.
 *
 * <p>Three decisions that are load-bearing:
 *
 * <ul>
 *   <li><b>Only accepted participants are ranked.</b> An unanswered invite is not a
 *       score of zero — it is somebody who has not replied, and ranking them last
 *       would report a result about a person who never took part.</li>
 *   <li><b>The clock starts when the first person accepts</b>, and later accepters are
 *       scored over that same window. Scores are derived from the activity log over
 *       [start, end] rather than from the moment of joining, so a late accepter gains
 *       nothing and loses nothing — which is what makes joining late fair at all.</li>
 *   <li><b>A challenge everyone declined is CANCELLED, not COMPLETED.</b> Declaring a
 *       winner among nobody would invent a result.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChallengeService {

    private final ChallengeRepository challengeRepository;
    private final ChallengeParticipantRepository participantRepository;
    private final NotificationService notificationService;
    private final ChallengeLeaderboardRepository leaderboardRepository;
    private final UserRepository userRepository;
    private final ActivityRepository activityRepository;
    private final FriendshipService friendshipService;

    /** Enough for a training group; beyond this the standings stop being readable. */
    private static final int MAX_PARTICIPANTS = 10;

    /** Also the answer for an id that isn't an account, so it can't be used to probe. */
    static final String NOT_FRIENDS = "You can only challenge your friends.";
    static final String H2H_DENIED =
            "Head-to-head is only available with friends and people you've competed against.";

    // ─────────────────────────────────────────────
    // 1. CREATE
    // ─────────────────────────────────────────────

    /**
     * Creates a challenge and invites one or more people to it.
     *
     * <p>Stays PENDING until somebody accepts. The creator is a participant from the
     * start and is ACCEPTED by definition — they proposed it.
     */
    @Transactional
    public Map<String, Object> createChallenge(String creatorId, List<String> opponentIds,
                                               ChallengeType type, String title,
                                               int durationDays) {
        User creator = userRepository.findById(creatorId)
                .orElseThrow(() -> new AccountNotFoundException(creatorId));

        if (type == null) {
            throw new RuntimeException("A challenge needs a type.");
        }

        // De-duplicated, and the creator removed if they listed themselves: inviting
        // the same person twice would violate the participant uniqueness constraint,
        // and a 500 from a constraint is a worse answer than simply ignoring it.
        LinkedHashSet<String> invitees = new LinkedHashSet<>(
                opponentIds == null ? List.of() : opponentIds);
        boolean listedSelf = invitees.remove(creatorId);

        if (invitees.isEmpty()) {
            // Distinguished, because "invite someone" is unhelpful advice to somebody
            // who just invited the only person they picked.
            throw new RuntimeException(listedSelf
                    ? "You cannot challenge yourself."
                    : "Invite at least one other person to the challenge.");
        }
        if (invitees.size() + 1 > MAX_PARTICIPANTS) {
            throw new RuntimeException("A challenge can hold at most " + MAX_PARTICIPANTS + " people.");
        }

        List<User> opponents = new ArrayList<>();
        for (String opponentId : invitees) {
            // Friends only (PLAN P4-4). One message for "not your friend" and "no such
            // user": "Opponent not found: <id>" told anyone which ids were real accounts.
            // Not AccountNotFoundException either way — somebody else's id says nothing
            // about the caller's own session (AGENT.md §5).
            if (!friendshipService.areFriends(creatorId, opponentId)) {
                throw new RuntimeException(NOT_FRIENDS);
            }
            opponents.add(userRepository.findById(opponentId)
                    .orElseThrow(() -> new RuntimeException(NOT_FRIENDS)));
        }

        // One live challenge at a time with any given person, so the standings can't
        // be split across two competitions nobody asked for. "With" means both of you
        // are still in it: someone who declined or left a running challenge is out of
        // it, and used to keep blocking a new one (PLAN P4-15).
        for (Challenge existing : challengeRepository.findAllByUserId(creatorId)) {
            if (existing.getStatus() != ChallengeStatus.ACTIVE
                    && existing.getStatus() != ChallengeStatus.PENDING) continue;
            if (!existing.isStillIn(creatorId)) continue;
            for (String opponentId : invitees) {
                if (existing.isStillIn(opponentId)) {
                    throw new RuntimeException(
                            "You already have an active or pending challenge with one of these people.");
                }
            }
        }

        int days = durationDays > 0 ? durationDays : 7;

        Challenge challenge = Challenge.builder()
                .creator(creator)
                .title(title != null && !title.isBlank() ? title : defaultTitle(creator, opponents, type))
                .type(type)
                .status(ChallengeStatus.PENDING)
                // Placeholder: reset to the real start when the first person accepts.
                .startDate(LocalDate.now())
                .endDate(LocalDate.now().plusDays(days))
                .build();

        challenge.addParticipant(ChallengeParticipant.builder()
                .user(creator)
                .status(ParticipantStatus.ACCEPTED)
                .isCreator(true)
                .respondedAt(LocalDateTime.now())
                .build());

        for (User opponent : opponents) {
            challenge.addParticipant(ChallengeParticipant.builder()
                    .user(opponent)
                    .status(ParticipantStatus.INVITED)
                    .isCreator(false)
                    .build());
        }

        Challenge saved = challengeRepository.save(challenge);

        for (User opponent : opponents) {
            notificationService.notify(opponent, NotificationType.GENERAL,
                    firstName(creator) + " challenged you",
                    saved.getTitle(),
                    "challenges",
                    "challenge-invite:" + saved.getId() + ":" + opponent.getId());
        }

        return buildChallengeResponse(saved, creatorId);
    }

    private String defaultTitle(User creator, List<User> opponents, ChallengeType type) {
        String metric = type.name().replace("_", " ");
        if (opponents.size() == 1) {
            return firstName(creator) + " vs " + firstName(opponents.get(0)) + " — " + metric;
        }
        return firstName(creator) + " + " + opponents.size() + " others — " + metric;
    }

    // ─────────────────────────────────────────────
    // 2. RESPOND
    // ─────────────────────────────────────────────

    /**
     * Accepts or declines an invitation.
     *
     * <p>The first acceptance starts the clock. Later ones join the window already
     * running, which is fair because every score is computed over the same window from
     * the activity log rather than from when somebody said yes.
     */
    @Transactional
    public Map<String, Object> respondToChallenge(String challengeId, String userId, boolean accept) {
        Challenge challenge = challengeRepository.findById(challengeId)
                .orElseThrow(() -> new RuntimeException("Challenge not found: " + challengeId));

        ChallengeParticipant participant = challenge.participantFor(userId)
                .orElseThrow(() -> new AccessDeniedException("You weren't invited to this challenge."));

        if (Boolean.TRUE.equals(participant.getIsCreator())) {
            throw new RuntimeException("You created this challenge — you're already in it.");
        }
        if (participant.getStatus() != ParticipantStatus.INVITED) {
            throw new RuntimeException("You've already responded to this challenge.");
        }
        if (challenge.getStatus() == ChallengeStatus.COMPLETED
                || challenge.getStatus() == ChallengeStatus.CANCELLED) {
            throw new RuntimeException("This challenge is over.");
        }

        participant.setStatus(accept ? ParticipantStatus.ACCEPTED : ParticipantStatus.DECLINED);
        participant.setRespondedAt(LocalDateTime.now());

        if (accept && challenge.getStatus() == ChallengeStatus.PENDING) {
            // The clock starts on the first acceptance, keeping the duration that was
            // agreed rather than the placeholder dates set at creation.
            LocalDate today = LocalDate.now();
            long duration = ChronoUnit.DAYS.between(challenge.getStartDate(), challenge.getEndDate());
            challenge.setStartDate(today);
            challenge.setEndDate(today.plusDays(duration));
            challenge.setStatus(ChallengeStatus.ACTIVE);
        }

        // Everyone said no. Completing it would declare the creator the winner of a
        // competition nobody entered.
        if (!accept && challenge.getStatus() == ChallengeStatus.PENDING && noInvitesLeft(challenge)) {
            challenge.setStatus(ChallengeStatus.DECLINED);
        }

        challengeRepository.save(challenge);
        return buildChallengeResponse(challenge, userId);
    }

    /** True when nobody is still deciding and nobody but the creator has accepted. */
    private boolean noInvitesLeft(Challenge challenge) {
        return challenge.getParticipants().stream()
                .filter(p -> !Boolean.TRUE.equals(p.getIsCreator()))
                .noneMatch(p -> p.getStatus() == ParticipantStatus.INVITED
                             || p.getStatus() == ParticipantStatus.ACCEPTED);
    }

    // ─────────────────────────────────────────────
    // 3. CANCEL (creator only, while PENDING)
    // ─────────────────────────────────────────────

    @Transactional
    public void cancelChallenge(String challengeId, String requestingUserId) {
        Challenge challenge = challengeRepository.findById(challengeId)
                .orElseThrow(() -> new RuntimeException("Challenge not found: " + challengeId));

        if (!challenge.getCreator().getId().equals(requestingUserId)) {
            throw new AccessDeniedException("Only the creator can cancel a challenge.");
        }
        if (challenge.getStatus() != ChallengeStatus.PENDING) {
            throw new RuntimeException("Only pending challenges can be cancelled.");
        }

        challenge.setStatus(ChallengeStatus.CANCELLED);
        challengeRepository.save(challenge);
    }

    /** Leaves a challenge that is already running, without ending it for everyone else. */
    @Transactional
    public Map<String, Object> leaveChallenge(String challengeId, String userId) {
        Challenge challenge = challengeRepository.findById(challengeId)
                .orElseThrow(() -> new RuntimeException("Challenge not found: " + challengeId));

        ChallengeParticipant participant = challenge.participantFor(userId)
                .orElseThrow(() -> new AccessDeniedException("You're not part of this challenge."));

        if (Boolean.TRUE.equals(participant.getIsCreator())) {
            throw new RuntimeException("The creator can't leave — cancel it instead.");
        }
        if (challenge.getStatus() == ChallengeStatus.COMPLETED) {
            throw new RuntimeException("This challenge is already over.");
        }
        // Only someone competing can leave. Without this, leaving a challenge you had
        // already declined answered "you left" again, and leaving one you were merely
        // invited to quietly declined it through the wrong door.
        if (participant.getStatus() == ParticipantStatus.DECLINED) {
            throw new RuntimeException("You're no longer part of this challenge.");
        }
        if (participant.getStatus() == ParticipantStatus.INVITED) {
            throw new RuntimeException("You haven't joined this challenge — decline the invite instead.");
        }

        participant.setStatus(ParticipantStatus.DECLINED);
        participant.setRespondedAt(LocalDateTime.now());
        challengeRepository.save(challenge);
        return buildChallengeResponse(challenge, userId);
    }

    // ─────────────────────────────────────────────
    // 4. NIGHTLY SCORE UPDATER  (runs every night at 23:55)
    // ─────────────────────────────────────────────

    @Scheduled(cron = "0 55 23 * * *")
    @Transactional
    public void updateAllActiveScores() {
        log.info("Running nightly challenge score update...");
        List<Challenge> active = challengeRepository.findByStatus(ChallengeStatus.ACTIVE);
        for (Challenge challenge : active) {
            updateScores(challenge);
            challengeRepository.save(challenge);
            saveLeaderboardSnapshot(challenge);
        }
        log.info("Nightly update complete — {} challenges updated.", active.size());
    }

    // ─────────────────────────────────────────────
    // 5. WINNER DECLARATION  (runs every night at 00:05)
    // ─────────────────────────────────────────────

    @Scheduled(cron = "0 5 0 * * *")
    @Transactional
    public void declareExpiredChallengeWinners() {
        log.info("Checking for expired challenges...");
        List<Challenge> expired = challengeRepository.findExpiredActiveChallenges(LocalDate.now());
        for (Challenge challenge : expired) {
            updateScores(challenge);   // final score refresh
            declareWinner(challenge);
            challengeRepository.save(challenge);
            // Settling a challenge at five past midnight and saying nothing meant the
            // result was only ever seen by whoever happened to reopen the panel
            // (PLAN P2-2).
            announceResult(challenge);
        }
        log.info("Winner declaration complete — {} challenges resolved.", expired.size());
    }

    // ─────────────────────────────────────────────
    // 6. READ ENDPOINTS
    // ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getUserChallenges(String userId) {
        return challengeRepository.findAllByUserId(userId).stream()
                .map(c -> { refreshIfActive(c); return buildChallengeResponse(c, userId); })
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getChallengeDetail(String challengeId, String requestingUserId) {
        Challenge challenge = challengeRepository.findById(challengeId)
                .orElseThrow(() -> new RuntimeException("Challenge not found: " + challengeId));
        requireParticipant(challenge, requestingUserId);
        refreshIfActive(challenge);
        return buildChallengeResponse(challenge, requestingUserId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getPendingInvites(String userId) {
        return challengeRepository.findPendingInvitesForUser(userId).stream()
                .map(c -> buildChallengeResponse(c, userId))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getLeaderboardHistory(String requestingUserId, String challengeId) {
        Challenge challenge = challengeRepository.findById(challengeId)
                .orElseThrow(() -> new RuntimeException("Challenge not found: " + challengeId));
        requireParticipant(challenge, requestingUserId);
        return leaderboardRepository.findByChallengeIdOrderBySnapshotDateAsc(challengeId)
                .stream().map(this::buildLeaderboardEntry).collect(Collectors.toList());
    }

    /**
     * Head-to-head record between the caller and one other person.
     *
     * <p>Only for someone the caller is friends with or has competed against (PLAN
     * P4-4). It used to answer for any id, which made it a lookup of any user's full
     * name, and its "User not found" told apart real ids from made-up ones. A stranger
     * and a non-existent id now get the same 403.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getH2HStats(String userAId, String userBId) {
        if (userAId.equals(userBId)) {
            // Every challenge involves you, so the arithmetic below went negative.
            throw new RuntimeException("Head-to-head needs another person.");
        }
        User userA = userRepository.findById(userAId)
                .orElseThrow(() -> new AccountNotFoundException(userAId));

        List<Challenge> history = challengeRepository.findH2HHistory(userAId, userBId);
        if (history.isEmpty() && !friendshipService.areFriends(userAId, userBId)) {
            throw new AccessDeniedException(H2H_DENIED);
        }
        User userB = userRepository.findById(userBId)
                .orElseThrow(() -> new AccessDeniedException(H2H_DENIED));

        long userAWins = history.stream()
                .filter(c -> c.getWinner() != null && c.getWinner().getId().equals(userAId)).count();
        long userBWins = history.stream()
                .filter(c -> c.getWinner() != null && c.getWinner().getId().equals(userBId)).count();
        long draws = history.stream()
                .filter(c -> c.getStatus() == ChallengeStatus.COMPLETED && c.getWinner() == null).count();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalChallenges", history.size());
        stats.put("userA", fullName(userA));
        stats.put("userB", fullName(userB));
        stats.put("userAWins", userAWins);
        stats.put("userBWins", userBWins);
        stats.put("draws", draws);
        // With groups, neither of them winning is now a real outcome — somebody else
        // in the challenge did.
        stats.put("otherWinners", history.size() - userAWins - userBWins - draws);
        stats.put("dominantPlayer", userAWins > userBWins ? firstName(userA)
                : userBWins > userAWins ? firstName(userB) : "Even");
        return stats;
    }

    // ─────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────

    /**
     * Recomputes an active challenge's scores for display.
     *
     * <p>Scores are otherwise only written by the 23:55 scheduled job, so everything
     * logged during the day was invisible until the next night — you could finish a
     * workout, open the challenge, and see yesterday's numbers with no indication
     * anything was missing. This runs inside a read-only transaction, so the fresh
     * values are used for the response but not flushed; the nightly job remains the
     * one that persists them and takes the leaderboard snapshot.
     */
    private void refreshIfActive(Challenge challenge) {
        if (challenge.getStatus() == ChallengeStatus.ACTIVE) {
            updateScores(challenge);
        }
    }

    /**
     * A challenge is private to the people in it. Participants are LAZY, so every
     * caller of this has to be inside a transaction — all are.
     */
    private void requireParticipant(Challenge challenge, String userId) {
        if (!challenge.involves(userId)) {
            throw new AccessDeniedException("You're not part of this challenge.");
        }
    }

    /** Recalculates every competitor's score from the database. */
    private void updateScores(Challenge challenge) {
        LocalDateTime from = challenge.getStartDate().atStartOfDay();
        LocalDateTime to = challenge.getEndDate().atTime(23, 59, 59);

        for (ChallengeParticipant participant : challenge.getParticipants()) {
            // An unanswered or declined invitation is not a score of zero — it is a
            // person who is not competing, and it stays out of the ranking entirely.
            if (!participant.isCompeting()) continue;
            participant.setScore(calculateScore(
                    challenge.getType(), participant.getUser().getId(), from, to));
        }
    }

    private double calculateScore(ChallengeType type, String userId,
                                  LocalDateTime from, LocalDateTime to) {
        List<Activity> activities = activityRepository.findByUserIdAndStartTimeBetween(userId, from, to);
        return switch (type) {
            case CALORIES_BURNED ->
                    activities.stream()
                            .filter(a -> a.getCaloriesBurned() != null)
                            .mapToDouble(Activity::getCaloriesBurned).sum();
            case WORKOUT_COUNT ->
                    activities.size();
            case TOTAL_DURATION ->
                    activities.stream()
                            .filter(a -> a.getDuration() != null)
                            .mapToDouble(Activity::getDuration).sum();
            case STREAK_LENGTH ->
                    calculateStreakDuringPeriod(activities, from.toLocalDate(), to.toLocalDate());
        };
    }

    /** Calculates the longest consecutive-day streak within a date range. */
    private double calculateStreakDuringPeriod(List<Activity> activities, LocalDate from, LocalDate to) {
        if (activities.isEmpty()) return 0;

        Set<LocalDate> activeDays = activities.stream()
                .filter(a -> a.getStartTime() != null)
                .map(a -> a.getStartTime().toLocalDate())
                .collect(Collectors.toSet());

        int maxStreak = 0, current = 0;
        LocalDate day = from;
        while (!day.isAfter(to)) {
            if (activeDays.contains(day)) {
                current++;
                maxStreak = Math.max(maxStreak, current);
            } else {
                current = 0;
            }
            day = day.plusDays(1);
        }
        return maxStreak;
    }

    /**
     * Ranks the competitors and names the winner.
     *
     * <p>A tie at the top is a draw however many people are in it, and a challenge
     * nobody accepted is cancelled rather than won.
     */
    private void declareWinner(Challenge challenge) {
        challenge.setStatus(ChallengeStatus.COMPLETED);

        List<ChallengeParticipant> ranked = rankedCompetitors(challenge);
        String metric = challenge.getType().name().replace("_", " ").toLowerCase();

        if (ranked.size() < 2) {
            challenge.setStatus(ChallengeStatus.CANCELLED);
            challenge.setWinner(null);
            challenge.setResultMessage("Nobody else joined, so there was no contest.");
            return;
        }

        ChallengeParticipant top = ranked.get(0);
        ChallengeParticipant second = ranked.get(1);

        if (scoreOf(top) == scoreOf(second)) {
            long tied = ranked.stream().filter(p -> scoreOf(p) == scoreOf(top)).count();
            challenge.setWinner(null);
            challenge.setResultMessage(String.format(
                    "It's a draw! %d tied on %.1f %s. Rematch?", tied, scoreOf(top), metric));
            return;
        }

        challenge.setWinner(top.getUser());
        challenge.setResultMessage(ranked.size() == 2
                ? String.format("%s wins! %s scored %.1f vs %s's %.1f %s.",
                        firstName(top.getUser()), firstName(top.getUser()), scoreOf(top),
                        firstName(second.getUser()), scoreOf(second), metric)
                : String.format("%s wins! %.1f %s, ahead of %s on %.1f, from %d competitors.",
                        firstName(top.getUser()), scoreOf(top), metric,
                        firstName(second.getUser()), scoreOf(second), ranked.size()));
    }

    /**
     * Tells every competitor how it ended.
     *
     * <p>Keyed on the challenge and the person, so a re-run of the settlement job cannot
     * announce the same result twice.
     */
    private void announceResult(Challenge challenge) {
        String message = challenge.getResultMessage();
        if (message == null || message.isBlank()) return;

        for (ChallengeParticipant participant : challenge.getParticipants()) {
            if (!participant.isCompeting()) continue;
            User user = participant.getUser();
            if (user == null) continue;

            boolean won = challenge.getWinner() != null
                    && challenge.getWinner().getId().equals(user.getId());
            // No winner means a draw only for a challenge that was actually contested.
            // One settled as CANCELLED ("nobody else joined") has no winner either, and
            // used to be announced as "ended in a draw" (PLAN P4-14).
            boolean cancelled = challenge.getStatus() == ChallengeStatus.CANCELLED;
            boolean draw = !cancelled && challenge.getWinner() == null;

            notificationService.notify(user, NotificationType.CHALLENGE_RESULT,
                    cancelled ? "Your challenge was cancelled"
                    : draw ? "Your challenge ended in a draw"
                         : won ? "You won your challenge" : "Your challenge has ended",
                    message,
                    "challenges",
                    "challenge-result:" + challenge.getId() + ":" + user.getId());
        }
    }

    private void saveLeaderboardSnapshot(Challenge challenge) {
        List<ChallengeParticipant> ranked = rankedCompetitors(challenge);
        if (ranked.isEmpty()) return;

        ChallengeParticipant leader = ranked.get(0);
        double margin = ranked.size() > 1 ? scoreOf(leader) - scoreOf(ranked.get(1)) : scoreOf(leader);

        List<Map<String, Object>> standings = new ArrayList<>(ranked.size());
        for (ChallengeParticipant p : ranked) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", p.getUser().getId());
            row.put("name", fullName(p.getUser()));
            row.put("score", scoreOf(p));
            standings.add(row);
        }

        leaderboardRepository.save(ChallengeLeaderboardEntry.builder()
                .challenge(challenge)
                .snapshotDate(LocalDate.now())
                .standings(standings)
                .leadingUserId(leader.getUser().getId())
                .leadMargin(margin)
                .build());
    }

    /** Accepted participants, highest score first. */
    private List<ChallengeParticipant> rankedCompetitors(Challenge challenge) {
        return challenge.competitors().stream()
                .sorted(Comparator.comparingDouble(this::scoreOf).reversed())
                .toList();
    }

    private double scoreOf(ChallengeParticipant participant) {
        return participant.getScore() != null ? participant.getScore() : 0.0;
    }

    private Map<String, Object> buildChallengeResponse(Challenge c, String viewingUserId) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", c.getId());
        map.put("title", c.getTitle());
        map.put("type", c.getType().name());
        map.put("status", c.getStatus().name());
        map.put("startDate", c.getStartDate());
        map.put("endDate", c.getEndDate());

        List<ChallengeParticipant> ranked = rankedCompetitors(c);

        // Standings: everyone invited, competitors ranked, non-competitors after them
        // with their status so the UI can grey them out rather than rank them last.
        List<Map<String, Object>> standings = new ArrayList<>();
        int position = 1;
        for (ChallengeParticipant p : ranked) {
            standings.add(participantRow(p, position++, viewingUserId));
        }
        for (ChallengeParticipant p : c.getParticipants()) {
            if (!p.isCompeting()) standings.add(participantRow(p, null, viewingUserId));
        }
        map.put("participants", standings);
        map.put("participantCount", ranked.size());

        Map<String, Object> creator = new LinkedHashMap<>();
        creator.put("id", c.getCreator().getId());
        creator.put("name", fullName(c.getCreator()));
        map.put("creator", creator);

        if (c.getStatus() == ChallengeStatus.ACTIVE && !ranked.isEmpty()) {
            ChallengeParticipant leader = ranked.get(0);
            double margin = ranked.size() > 1 ? scoreOf(leader) - scoreOf(ranked.get(1)) : 0.0;
            map.put("currentLeader", margin > 0 ? firstName(leader.getUser()) : "Tied");
            map.put("leadMargin", margin);
            map.put("daysRemaining", Math.max(0, ChronoUnit.DAYS.between(LocalDate.now(), c.getEndDate())));
        }

        if (c.getStatus() == ChallengeStatus.COMPLETED) {
            map.put("winner", c.getWinner() != null ? fullName(c.getWinner()) : "Draw");
            map.put("resultMessage", c.getResultMessage());
        } else if (c.getStatus() == ChallengeStatus.CANCELLED && c.getResultMessage() != null) {
            // Settled with no contest ("Nobody else joined"). The message says why, and
            // was withheld with the rest, so nobody could tell (PLAN P4-14). No `winner`:
            // there isn't one, and "Draw" would be wrong. A challenge its creator
            // cancelled has no message and adds nothing.
            map.put("resultMessage", c.getResultMessage());
        }

        // Perspective — what the viewer needs to know about their own standing.
        c.participantFor(viewingUserId).ifPresent(me -> {
            map.put("yourScore", scoreOf(me));
            map.put("yourStatus", me.getStatus().name());
            map.put("youAreCreator", Boolean.TRUE.equals(me.getIsCreator()));
            int myPosition = ranked.indexOf(me);
            map.put("yourPosition", myPosition >= 0 ? myPosition + 1 : null);
        });

        return map;
    }

    private Map<String, Object> participantRow(ChallengeParticipant p, Integer position, String viewingUserId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("userId", p.getUser().getId());
        row.put("name", fullName(p.getUser()));
        row.put("score", scoreOf(p));
        row.put("status", p.getStatus().name());
        row.put("isCreator", Boolean.TRUE.equals(p.getIsCreator()));
        row.put("isYou", p.getUser().getId().equals(viewingUserId));
        row.put("position", position);
        return row;
    }

    private Map<String, Object> buildLeaderboardEntry(ChallengeLeaderboardEntry e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("date", e.getSnapshotDate());
        map.put("standings", e.getStandings());
        map.put("leadingUserId", e.getLeadingUserId());
        map.put("leadMargin", e.getLeadMargin());
        return map;
    }

    private String firstName(User user) {
        if (user == null) return "Someone";
        return user.getFirstName() != null && !user.getFirstName().isBlank()
                ? user.getFirstName() : "Athlete";
    }

    private String fullName(User user) {
        if (user == null) return "Someone";
        String name = ((user.getFirstName() != null ? user.getFirstName() : "") + " "
                     + (user.getLastName() != null ? user.getLastName() : "")).trim();
        return name.isEmpty() ? "Athlete" : name;
    }
}
