package com.project.fitness.service;

import com.project.fitness.exceptions.AccountNotFoundException;
import com.project.fitness.model.Friendship;
import com.project.fitness.model.FriendshipStatus;
import com.project.fitness.model.NotificationType;
import com.project.fitness.model.User;
import com.project.fitness.repository.FriendshipRepository;
import com.project.fitness.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Friends (PLAN P2-4).
 *
 * <p>Challenges shipped before any concept of a friend, so the only way to challenge
 * somebody was to know their raw UUID — the Profile panel has a "Your Athlete ID —
 * share to receive challenges" box precisely because there was no other way. This
 * replaces that with asking for someone by the address they signed up with.
 *
 * <p><b>Adding by email does not leak who has an account.</b> A request for an unknown
 * address answers exactly as one for a known address does, for the same reason
 * {@code PasswordResetService} does it: otherwise this endpoint is a free "is this
 * person a member here" oracle that anyone with a list of addresses can run.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FriendshipService {

    private final FriendshipRepository friendshipRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    /** Said back for every add attempt, known address or not. */
    private static final String REQUEST_SENT =
            "If that address belongs to a FitPilot account, a friend request is on its way.";

    // ── Requesting ───────────────────────────────────────────────────────────

    /**
     * Sends a friend request by email address.
     *
     * <p>Accepting an already-pending request from the other direction rather than
     * creating a second row: two people who ask each other at the same moment have
     * plainly agreed, and leaving both requests pending would strand them behind two
     * invites neither can resolve.
     */
    @Transactional
    public Map<String, Object> requestByEmail(String requesterId, String email) {
        User requester = userRepository.findById(requesterId)
                .orElseThrow(() -> new AccountNotFoundException(requesterId));

        if (email == null || email.isBlank()) {
            throw new RuntimeException("Enter the email address of the person you want to add.");
        }
        String normalised = email.trim().toLowerCase(Locale.ROOT);

        if (normalised.equalsIgnoreCase(requester.getEmail())) {
            // Safe to be specific: the caller already knows their own address exists.
            throw new RuntimeException("That's your own address.");
        }

        // UserRepository.findByEmail returns null rather than an Optional.
        User addressee = userRepository.findByEmail(normalised);
        if (addressee == null) {
            // Deliberately indistinguishable from success — see the class note.
            log.info("Friend request to an unknown address from user {}", requesterId);
            return message(REQUEST_SENT);
        }
        Optional<Friendship> existing = friendshipRepository.findBetween(requesterId, addressee.getId());

        if (existing.isPresent()) {
            Friendship friendship = existing.get();
            if (friendship.getStatus() == FriendshipStatus.ACCEPTED) {
                return message("You're already friends with them.");
            }
            // They asked first and this is the answer, in substance if not in form.
            if (friendship.getAddressee().getId().equals(requesterId)) {
                return accept(requesterId, friendship.getId());
            }
            return message(REQUEST_SENT);
        }

        Friendship friendship = friendshipRepository.save(Friendship.builder()
                .requester(requester)
                .addressee(addressee)
                .status(FriendshipStatus.PENDING)
                .build());

        notificationService.notify(addressee, NotificationType.GENERAL,
                "Friend request from " + displayName(requester),
                displayName(requester) + " wants to connect on FitPilot.",
                "challenges",
                "friend-request:" + friendship.getId());

        return message(REQUEST_SENT);
    }

    // ── Answering ────────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> accept(String requestingUserId, String friendshipId) {
        Friendship friendship = load(friendshipId);

        // Only the person who was asked can accept. Without this, the requester could
        // accept on the other party's behalf and add themselves to anyone's friends.
        if (!friendship.getAddressee().getId().equals(requestingUserId)) {
            throw new AccessDeniedException("Only the person who was asked can accept this request.");
        }
        if (friendship.getStatus() == FriendshipStatus.ACCEPTED) {
            return message("You're already friends.");
        }

        friendship.setStatus(FriendshipStatus.ACCEPTED);
        friendship.setRespondedAt(LocalDateTime.now());
        friendshipRepository.save(friendship);

        User accepter = friendship.getAddressee();
        notificationService.notify(friendship.getRequester(), NotificationType.GENERAL,
                displayName(accepter) + " accepted your friend request",
                "You can now challenge each other directly.",
                "challenges",
                "friend-accepted:" + friendship.getId());

        return message("You're now friends with " + displayName(friendship.getRequester().getId()
                .equals(requestingUserId) ? friendship.getAddressee() : friendship.getRequester()) + ".");
    }

    /**
     * Declines a request, or removes an existing friendship.
     *
     * <p>Both are a delete. A declined request is not remembered: keeping it would
     * either show the requester "they said no", or hide a row that then silently stops
     * them ever asking again, because the pair is unique.
     */
    @Transactional
    public void removeOrDecline(String requestingUserId, String friendshipId) {
        Friendship friendship = load(friendshipId);

        // Either party may walk away — but only those two.
        if (!friendship.involves(requestingUserId)) {
            throw new AccessDeniedException("You don't have access to this friendship.");
        }
        friendshipRepository.delete(friendship);
    }

    // ── Reading ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getFriends(String userId) {
        return friendshipRepository.findAllForUser(userId, FriendshipStatus.ACCEPTED).stream()
                .map(f -> toMap(f, userId))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getPendingInvites(String userId) {
        return friendshipRepository.findPendingFor(userId).stream()
                .map(f -> toMap(f, userId))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getSentRequests(String userId) {
        return friendshipRepository.findSentBy(userId).stream()
                .map(f -> toMap(f, userId))
                .toList();
    }

    /** True when two users are connected — the check a group challenge invite makes. */
    @Transactional(readOnly = true)
    public boolean areFriends(String a, String b) {
        return friendshipRepository.findBetween(a, b)
                .map(f -> f.getStatus() == FriendshipStatus.ACCEPTED)
                .orElse(false);
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private Friendship load(String friendshipId) {
        return friendshipRepository.findById(friendshipId)
                .orElseThrow(() -> new RuntimeException("Friend request not found: " + friendshipId));
    }

    /**
     * One side of a friendship, from the caller's point of view.
     *
     * <p>Carries the other person's id, name and streak — and deliberately not their
     * email. The caller already knows it if they sent the request, and a friends list
     * is not a reason to hand out the address of everyone who accepted one.
     */
    private Map<String, Object> toMap(Friendship friendship, String viewerId) {
        User other = friendship.otherThan(viewerId);

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("friendshipId", friendship.getId());
        map.put("userId", other != null ? other.getId() : null);
        map.put("name", displayName(other));
        map.put("currentStreak", other != null ? other.getCurrentStreak() : null);
        map.put("status", friendship.getStatus().name());
        map.put("isIncoming", friendship.getAddressee() != null
                && friendship.getAddressee().getId().equals(viewerId));
        map.put("createdAt", friendship.getCreatedAt());
        map.put("respondedAt", friendship.getRespondedAt());
        return map;
    }

    private String displayName(User user) {
        if (user == null) return "Someone";
        String name = ((user.getFirstName() != null ? user.getFirstName() : "") + " "
                     + (user.getLastName() != null ? user.getLastName() : "")).trim();
        return name.isEmpty() ? "Athlete" : name;
    }

    private Map<String, Object> message(String message) {
        return Map.of("message", message);
    }
}
