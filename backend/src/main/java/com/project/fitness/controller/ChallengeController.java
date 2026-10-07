package com.project.fitness.controller;

import com.project.fitness.model.ChallengeType;
import com.project.fitness.security.CurrentUser;
import com.project.fitness.service.ChallengeService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Feature 14 — Workout Buddy / Challenge API
 *
 * POST /api/challenges                          — send a challenge invite
 * POST /api/challenges/{id}/respond             — accept or decline
 * DELETE /api/challenges/{id}/cancel            — cancel a pending challenge
 * GET  /api/challenges/user/{userId}            — all challenges for a user
 * GET  /api/challenges/{id}                     — single challenge detail
 * GET  /api/challenges/invites/{userId}         — pending invites for a user
 * GET  /api/challenges/{id}/leaderboard         — daily score history (graph data)
 * GET  /api/challenges/h2h/{userAId}/{userBId}  — head-to-head all-time stats
 */
@RestController
@RequestMapping("/api/challenges")
@RequiredArgsConstructor
public class ChallengeController {

    private final ChallengeService challengeService;

    /**
     * Sends a challenge invite to one or more people.
     *
     * <p>Takes {@code opponentIds} now that a challenge can hold a group (PLAN P2-4).
     * The old single {@code opponentId} field is still read, so a client that predates
     * groups keeps working unchanged — see {@link CreateChallengeRequest#opponents()}.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createChallenge(@RequestBody CreateChallengeRequest req) {
        // The creator is always the caller — creatorId in the body is ignored.
        return ResponseEntity.ok(challengeService.createChallenge(
                CurrentUser.id(),
                req.opponents(),
                req.getType(),
                req.getTitle(),
                req.getDurationDays() > 0 ? req.getDurationDays() : 7
        ));
    }

    /** Leaves a challenge that is already running, without ending it for the others. */
    @PostMapping("/{challengeId}/leave")
    public ResponseEntity<Map<String, Object>> leave(@PathVariable String challengeId) {
        return ResponseEntity.ok(challengeService.leaveChallenge(challengeId, CurrentUser.id()));
    }

    /** Accept or decline a pending invite */
    @PostMapping("/{challengeId}/respond")
    public ResponseEntity<Map<String, Object>> respond(
            @PathVariable String challengeId,
            @RequestBody RespondRequest req) {
        return ResponseEntity.ok(
                challengeService.respondToChallenge(challengeId, CurrentUser.id(), req.isAccept()));
    }

    /** Creator cancels a pending challenge */
    @DeleteMapping("/{challengeId}/cancel")
    public ResponseEntity<Map<String, String>> cancel(
            @PathVariable String challengeId,
            @RequestParam(required = false) String userId) {
        challengeService.cancelChallenge(challengeId, CurrentUser.id());
        return ResponseEntity.ok(Map.of("message", "Challenge cancelled."));
    }

    /** All challenges (any status) for a user */
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<Map<String, Object>>> getUserChallenges(@PathVariable String userId) {
        return ResponseEntity.ok(challengeService.getUserChallenges(CurrentUser.requireSelf(userId)));
    }

    /** Full detail of one challenge */
    @GetMapping("/{challengeId}")
    public ResponseEntity<Map<String, Object>> getChallengeDetail(
            @PathVariable String challengeId,
            @RequestParam(required = false) String viewingUserId) {
        // viewingUserId decided whose "yourScore/theirScore" perspective was returned,
        // and was not checked at all — so any challenge between any two users could be
        // read by anyone. The perspective is now the caller's, and the service rejects
        // callers who aren't in the challenge.
        return ResponseEntity.ok(challengeService.getChallengeDetail(challengeId, CurrentUser.id()));
    }

    /** Pending invites waiting for this user's response */
    @GetMapping("/invites/{userId}")
    public ResponseEntity<List<Map<String, Object>>> getPendingInvites(@PathVariable String userId) {
        return ResponseEntity.ok(challengeService.getPendingInvites(CurrentUser.requireSelf(userId)));
    }

    /** Daily score snapshots — use this as graph data on the frontend */
    @GetMapping("/{challengeId}/leaderboard")
    public ResponseEntity<List<Map<String, Object>>> getLeaderboard(@PathVariable String challengeId) {
        return ResponseEntity.ok(challengeService.getLeaderboardHistory(CurrentUser.id(), challengeId));
    }

    /** All-time head-to-head stats between two specific users */
    @GetMapping("/h2h/{userAId}/{userBId}")
    public ResponseEntity<Map<String, Object>> getH2HStats(
            @PathVariable String userAId,
            @PathVariable String userBId) {
        // Head-to-head is always "me vs them" — userAId must be the caller.
        return ResponseEntity.ok(
                challengeService.getH2HStats(CurrentUser.requireSelf(userAId), userBId));
    }

    // ─── Request bodies ───────────────────────────────────────

    @Data
    static class CreateChallengeRequest {
        private String creatorId;            // accepted for backwards compatibility, but ignored
        /** Pre-groups clients send this one id; still honoured. */
        private String opponentId;
        /** Groups send this. Either may be used; both are merged. */
        private java.util.List<String> opponentIds;

        /**
         * Everyone being invited, from whichever field the client filled in.
         *
         * <p>Both are read rather than one deprecating the other, because the frontend
         * and any saved API examples still send {@code opponentId} — dropping it would
         * have broken every existing 1-v-1 call for no gain.
         */
        java.util.List<String> opponents() {
            java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
            if (opponentId != null && !opponentId.isBlank()) all.add(opponentId.trim());
            if (opponentIds != null) {
                opponentIds.stream()
                        .filter(id -> id != null && !id.isBlank())
                        .map(String::trim)
                        .forEach(all::add);
            }
            return new java.util.ArrayList<>(all);
        }
        private ChallengeType type;          // CALORIES_BURNED | WORKOUT_COUNT | TOTAL_DURATION | STREAK_LENGTH
        private String title;                // optional — auto-generated if blank
        private int durationDays;            // default 7 if 0
    }

    @Data
    static class RespondRequest {
        private String userId;    // ignored — the responder is the authenticated user
        private boolean accept;
    }
}
