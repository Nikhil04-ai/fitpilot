package com.project.fitness.controller;

import com.project.fitness.security.CurrentUser;
import com.project.fitness.service.FriendshipService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Friends (PLAN P2-4)  →  /api/friends
 *
 * <p>Identity from the JWT throughout. A friendship is addressed by its own id and the
 * service checks that the caller is one of the two people in it.
 */
@RestController
@RequestMapping("/api/friends")
@RequiredArgsConstructor
public class FriendController {

    private final FriendshipService friendshipService;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> friends() {
        return ResponseEntity.ok(friendshipService.getFriends(CurrentUser.id()));
    }

    /** Requests waiting on the caller to answer. */
    @GetMapping("/invites")
    public ResponseEntity<List<Map<String, Object>>> invites() {
        return ResponseEntity.ok(friendshipService.getPendingInvites(CurrentUser.id()));
    }

    /** Requests the caller has sent and that are still unanswered. */
    @GetMapping("/sent")
    public ResponseEntity<List<Map<String, Object>>> sent() {
        return ResponseEntity.ok(friendshipService.getSentRequests(CurrentUser.id()));
    }

    /**
     * Adds someone by the address they signed up with.
     *
     * <p>Answers identically whether or not that address has an account — otherwise
     * this is a free "does this person use FitPilot" lookup for anyone with a list of
     * email addresses.
     */
    @PostMapping("/request")
    public ResponseEntity<Map<String, Object>> request(@RequestBody FriendRequest body) {
        return ResponseEntity.ok(friendshipService.requestByEmail(CurrentUser.id(), body.getEmail()));
    }

    @PostMapping("/{friendshipId}/accept")
    public ResponseEntity<Map<String, Object>> accept(@PathVariable String friendshipId) {
        return ResponseEntity.ok(friendshipService.accept(CurrentUser.id(), friendshipId));
    }

    /** Declines a pending request, or removes an existing friend. Both are a delete. */
    @DeleteMapping("/{friendshipId}")
    public ResponseEntity<Void> remove(@PathVariable String friendshipId) {
        friendshipService.removeOrDecline(CurrentUser.id(), friendshipId);
        return ResponseEntity.noContent().build();
    }

    @Data static class FriendRequest { private String email; }
}
