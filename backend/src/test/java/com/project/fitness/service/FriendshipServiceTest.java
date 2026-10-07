package com.project.fitness.service;

import com.project.fitness.model.Friendship;
import com.project.fitness.model.FriendshipStatus;
import com.project.fitness.model.NotificationType;
import com.project.fitness.model.User;
import com.project.fitness.repository.FriendshipRepository;
import com.project.fitness.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for FriendshipService — PLAN P2-4.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FriendshipService Tests")
class FriendshipServiceTest {

    @Mock private FriendshipRepository friendshipRepository;
    @Mock private UserRepository userRepository;
    @Mock private NotificationService notificationService;
    @InjectMocks private FriendshipService friendshipService;

    private User me;
    private User them;

    @BeforeEach
    void setUp() {
        me   = User.builder().id("me-1").email("me@test.com").firstName("Nikhil").lastName("Mehta").build();
        them = User.builder().id("them-1").email("them@test.com").firstName("Rahul").lastName("Sharma").build();
        lenient().when(userRepository.findById("me-1")).thenReturn(Optional.of(me));
        lenient().when(friendshipRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private Friendship pending(User requester, User addressee) {
        return Friendship.builder()
                .id("f-1").requester(requester).addressee(addressee)
                .status(FriendshipStatus.PENDING).build();
    }

    // ── Requesting ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Sending a request")
    class Requesting {

        @Test
        @DisplayName("creates a pending friendship and tells the other person")
        void createsAndNotifies() {
            when(userRepository.findByEmail("them@test.com")).thenReturn(them);
            when(friendshipRepository.findBetween("me-1", "them-1")).thenReturn(Optional.empty());

            friendshipService.requestByEmail("me-1", "  Them@Test.com  ");

            verify(friendshipRepository).save(any(Friendship.class));
            verify(notificationService).notify(eq(them), eq(NotificationType.GENERAL),
                    contains("Friend request from Nikhil Mehta"), anyString(),
                    eq("challenges"), anyString());
        }

        /**
         * The property that matters most here. An endpoint that answers differently for
         * a known and an unknown address is a free "does this person have an account"
         * lookup for anyone holding a list of email addresses — the same reason
         * PasswordResetService answers identically either way.
         */
        @Test
        @DisplayName("answers identically for an address with no account")
        void unknownAddressIsIndistinguishable() {
            when(userRepository.findByEmail("them@test.com")).thenReturn(them);
            when(friendshipRepository.findBetween("me-1", "them-1")).thenReturn(Optional.empty());
            Map<String, Object> known = friendshipService.requestByEmail("me-1", "them@test.com");

            when(userRepository.findByEmail("ghost@test.com")).thenReturn(null);
            Map<String, Object> unknown = friendshipService.requestByEmail("me-1", "ghost@test.com");

            assertThat(unknown).isEqualTo(known);
        }

        @Test
        @DisplayName("two people who ask each other at once simply become friends")
        void crossingRequestsResolveToAcceptance() {
            // They asked first; this request is the answer in substance if not in form.
            Friendship theirs = pending(them, me);
            when(userRepository.findByEmail("them@test.com")).thenReturn(them);
            when(friendshipRepository.findBetween("me-1", "them-1")).thenReturn(Optional.of(theirs));
            when(friendshipRepository.findById("f-1")).thenReturn(Optional.of(theirs));

            friendshipService.requestByEmail("me-1", "them@test.com");

            // Leaving both pending would strand them behind two invites neither can
            // resolve — and the unique pair constraint means a second row is impossible.
            assertThat(theirs.getStatus()).isEqualTo(FriendshipStatus.ACCEPTED);
        }

        @Test
        @DisplayName("says so plainly when they're already friends")
        void alreadyFriends() {
            Friendship accepted = pending(me, them);
            accepted.setStatus(FriendshipStatus.ACCEPTED);
            when(userRepository.findByEmail("them@test.com")).thenReturn(them);
            when(friendshipRepository.findBetween("me-1", "them-1")).thenReturn(Optional.of(accepted));

            Map<String, Object> result = friendshipService.requestByEmail("me-1", "them@test.com");

            assertThat((String) result.get("message")).contains("already friends");
            verify(friendshipRepository, never()).save(any());
        }

        @Test
        @DisplayName("adding your own address is refused, and can be said plainly")
        void selfRequestRejected() {
            // Safe to be specific here: the caller already knows their own address exists.
            assertThatThrownBy(() -> friendshipService.requestByEmail("me-1", "me@test.com"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("your own address");
        }

        @Test
        @DisplayName("an empty address is refused")
        void blankAddressRejected() {
            assertThatThrownBy(() -> friendshipService.requestByEmail("me-1", "  "))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("email address");
        }
    }

    // ── Answering ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Answering a request")
    class Answering {

        @Test
        @DisplayName("the addressee accepts, and the requester is told")
        void addresseeAccepts() {
            Friendship theirs = pending(them, me);
            when(friendshipRepository.findById("f-1")).thenReturn(Optional.of(theirs));

            friendshipService.accept("me-1", "f-1");

            assertThat(theirs.getStatus()).isEqualTo(FriendshipStatus.ACCEPTED);
            assertThat(theirs.getRespondedAt()).isNotNull();
            verify(notificationService).notify(eq(them), eq(NotificationType.GENERAL),
                    contains("accepted your friend request"), anyString(), eq("challenges"), anyString());
        }

        @Test
        @DisplayName("the requester cannot accept on the other person's behalf")
        void requesterCannotAcceptOwnRequest() {
            Friendship mine = pending(me, them);
            when(friendshipRepository.findById("f-1")).thenReturn(Optional.of(mine));

            // Without this check, anyone could add themselves to anyone's friends list.
            assertThatThrownBy(() -> friendshipService.accept("me-1", "f-1"))
                    .isInstanceOf(AccessDeniedException.class);
            verify(friendshipRepository, never()).save(any());
        }

        @Test
        @DisplayName("declining deletes the row rather than remembering the refusal")
        void decliningDeletes() {
            Friendship theirs = pending(them, me);
            when(friendshipRepository.findById("f-1")).thenReturn(Optional.of(theirs));

            friendshipService.removeOrDecline("me-1", "f-1");

            // Keeping it would either show the requester "they said no", or hide a row
            // that then silently stops them ever asking again — the pair is unique.
            verify(friendshipRepository).delete(theirs);
        }

        @Test
        @DisplayName("a stranger can neither decline nor remove the friendship")
        void strangerDenied() {
            when(friendshipRepository.findById("f-1")).thenReturn(Optional.of(pending(me, them)));

            assertThatThrownBy(() -> friendshipService.removeOrDecline("stranger-1", "f-1"))
                    .isInstanceOf(AccessDeniedException.class);
            verify(friendshipRepository, never()).delete(any());
        }
    }

    // ── Reading ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Reading the list")
    class Reading {

        @Test
        @DisplayName("shows the other person, whichever side of the row they're on")
        void showsTheOtherPerson() {
            Friendship iAsked = Friendship.builder()
                    .id("f-1").requester(me).addressee(them)
                    .status(FriendshipStatus.ACCEPTED).build();
            Friendship theyAsked = Friendship.builder()
                    .id("f-2").requester(them).addressee(me)
                    .status(FriendshipStatus.ACCEPTED).build();
            when(friendshipRepository.findAllForUser("me-1", FriendshipStatus.ACCEPTED))
                    .thenReturn(List.of(iAsked, theyAsked));

            List<Map<String, Object>> friends = friendshipService.getFriends("me-1");

            // One row per pair, so the direction is an implementation detail the list
            // has to see past.
            assertThat(friends).hasSize(2);
            assertThat(friends).allSatisfy(f -> assertThat(f.get("userId")).isEqualTo("them-1"));
            assertThat(friends.get(0).get("name")).isEqualTo("Rahul Sharma");
        }

        @Test
        @DisplayName("never hands out a friend's email address")
        void doesNotLeakEmail() {
            when(friendshipRepository.findAllForUser("me-1", FriendshipStatus.ACCEPTED))
                    .thenReturn(List.of(Friendship.builder()
                            .id("f-1").requester(me).addressee(them)
                            .status(FriendshipStatus.ACCEPTED).build()));

            Map<String, Object> friend = friendshipService.getFriends("me-1").get(0);

            // A friends list is not a reason to hand out the address of everyone who
            // accepted a request.
            assertThat(friend).doesNotContainKey("email");
            assertThat(friend.values()).doesNotContain("them@test.com");
        }

        @Test
        @DisplayName("marks which pending requests are incoming")
        void marksIncomingRequests() {
            when(friendshipRepository.findPendingFor("me-1")).thenReturn(List.of(pending(them, me)));

            Map<String, Object> invite = friendshipService.getPendingInvites("me-1").get(0);

            assertThat(invite.get("isIncoming")).isEqualTo(true);
            assertThat(invite.get("name")).isEqualTo("Rahul Sharma");
        }

        @Test
        @DisplayName("areFriends is true only once the request was accepted")
        void areFriendsRequiresAcceptance() {
            when(friendshipRepository.findBetween("me-1", "them-1"))
                    .thenReturn(Optional.of(pending(me, them)));
            assertThat(friendshipService.areFriends("me-1", "them-1")).isFalse();

            Friendship accepted = pending(me, them);
            accepted.setStatus(FriendshipStatus.ACCEPTED);
            when(friendshipRepository.findBetween("me-1", "them-1")).thenReturn(Optional.of(accepted));
            assertThat(friendshipService.areFriends("me-1", "them-1")).isTrue();
        }
    }
}
