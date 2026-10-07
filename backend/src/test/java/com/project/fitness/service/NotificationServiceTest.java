package com.project.fitness.service;

import com.project.fitness.model.Goal;
import com.project.fitness.model.GoalType;
import com.project.fitness.model.Notification;
import com.project.fitness.model.NotificationType;
import com.project.fitness.model.User;
import com.project.fitness.repository.GoalRepository;
import com.project.fitness.repository.NotificationRepository;
import com.project.fitness.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for NotificationService — PLAN P2-2.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationService Tests")
class NotificationServiceTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private UserRepository userRepository;
    @Mock private GoalRepository goalRepository;
    @InjectMocks private NotificationService notificationService;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id("user-1").firstName("Nikhil").build();
        lenient().when(notificationRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    // ── Producing ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Recording a notification")
    class Producing {

        @Test
        @DisplayName("stores the type, body and target panel")
        void storesTheNotification() {
            Notification saved = notificationService.notify(user, NotificationType.PLATEAU,
                    "Your running has plateaued", "Try more intensity.", "insights", "plateau:RUNNING:x");

            assertThat(saved).isNotNull();
            assertThat(saved.getType()).isEqualTo(NotificationType.PLATEAU);
            // A panel name, not a URL — the frontend owns the routing table and a stored
            // URL would be a second copy of it going stale in the database.
            assertThat(saved.getTargetPanel()).isEqualTo("insights");
            assertThat(saved.getReadAt()).isNull();
        }

        @Test
        @DisplayName("skips a dedupe key that has already been used")
        void skipsDuplicates() {
            when(notificationRepository.existsByUserIdAndDedupeKey("user-1", "goal-deadline:g1:2026-10-01"))
                    .thenReturn(true);

            Notification result = notificationService.notify(user, NotificationType.GOAL_DEADLINE,
                    "A goal is due", "body", "goals", "goal-deadline:g1:2026-10-01");

            // A goal due in five days is still due in four tomorrow. A feed that says so
            // every morning is a feed people turn off.
            assertThat(result).isNull();
            verify(notificationRepository, never()).save(any());
        }

        /**
         * A notification is a side effect of something more important — settling a
         * challenge, writing a digest. Failing to announce the result must never roll
         * back the result itself.
         */
        @Test
        @DisplayName("never throws into its caller when the write fails")
        void neverThrowsIntoTheCaller() {
            when(notificationRepository.save(any())).thenThrow(new RuntimeException("db is down"));

            Notification result = notificationService.notify(user, NotificationType.CHALLENGE_RESULT,
                    "You won", "body", "challenges", null);

            assertThat(result).isNull();
        }
    }

    // ── Reading and updating ─────────────────────────────────────────────────

    @Nested
    @DisplayName("The feed")
    class Feed {

        @Test
        @DisplayName("counts unread in the database rather than loading rows to filter")
        void unreadCountIsAQuery() {
            when(notificationRepository.countByUserIdAndReadAtIsNull("user-1")).thenReturn(4L);

            assertThat(notificationService.unreadCount("user-1")).isEqualTo(4);

            // The badge polls this, so it must not grow with the user's history
            // (AGENT.md §9).
            verify(notificationRepository, never()).findByUserIdOrderByCreatedAtDesc(anyString(), any());
        }

        @Test
        @DisplayName("marking one read stamps the time, and is idempotent")
        void markReadIsIdempotent() {
            Notification n = Notification.builder()
                    .id("n-1").user(user).type(NotificationType.GENERAL).title("Hi").build();
            when(notificationRepository.findById("n-1")).thenReturn(Optional.of(n));

            notificationService.markRead("user-1", "n-1");
            LocalDateTime firstRead = n.getReadAt();
            assertThat(firstRead).isNotNull();

            notificationService.markRead("user-1", "n-1");

            // A timestamp rather than a flag, so "when" survives — and re-reading must
            // not move it.
            assertThat(n.getReadAt()).isEqualTo(firstRead);
            verify(notificationRepository, times(1)).save(any());
        }

        @Test
        @DisplayName("another user cannot read — and so silence — this user's notification")
        void markReadDeniedForNonOwner() {
            Notification n = Notification.builder().id("n-1").user(user).build();
            when(notificationRepository.findById("n-1")).thenReturn(Optional.of(n));

            assertThatThrownBy(() -> notificationService.markRead("someone-else", "n-1"))
                    .isInstanceOf(AccessDeniedException.class);
            verify(notificationRepository, never()).save(any());
        }

        @Test
        @DisplayName("marking all read is one statement, not one per row")
        void markAllReadIsOneStatement() {
            when(notificationRepository.markAllRead(eq("user-1"), any())).thenReturn(12);

            assertThat(notificationService.markAllRead("user-1")).isEqualTo(12);

            // Loading a hundred rows to set one column on each is the pattern §9 exists
            // to stop, and this is the action guaranteed to touch every unread row.
            verify(notificationRepository, never()).save(any());
        }
    }

    // ── Deadline warnings ────────────────────────────────────────────────────

    @Nested
    @DisplayName("The daily deadline warning")
    class DeadlineWarnings {

        private Goal dueIn(long days) {
            return Goal.builder()
                    .id("goal-1").user(user).type(GoalType.WORKOUTS_PER_WEEK)
                    .targetValue(5.0).currentValue(2.0).isCompleted(false)
                    .deadline(LocalDate.now().plusDays(days))
                    .build();
        }

        @Test
        @DisplayName("queries the window rather than scanning every goal in the database")
        void queriesTheWindow() {
            when(goalRepository.findDueBetween(any(), any())).thenReturn(List.of());

            notificationService.warnAboutApproachingDeadlines();

            // findAll() would load every goal in the database each morning to find the
            // handful actually due (AGENT.md §9).
            verify(goalRepository, never()).findAll();
            verify(goalRepository).findDueBetween(LocalDate.now(), LocalDate.now().plusDays(7));
        }

        @Test
        @DisplayName("names the days left and carries the current progress")
        void warnsWithProgress() {
            when(goalRepository.findDueBetween(any(), any())).thenReturn(List.of(dueIn(3)));

            notificationService.warnAboutApproachingDeadlines();

            ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository).save(saved.capture());
            assertThat(saved.getValue().getTitle()).isEqualTo("A goal is due in 3 days");
            assertThat(saved.getValue().getBody()).contains("2 of 5");
            assertThat(saved.getValue().getTargetPanel()).isEqualTo("goals");
        }

        @Test
        @DisplayName("keys on the goal and its deadline, so it warns once rather than daily")
        void dedupeKeyIncludesTheDeadline() {
            Goal goal = dueIn(5);
            when(goalRepository.findDueBetween(any(), any())).thenReturn(List.of(goal));

            notificationService.warnAboutApproachingDeadlines();

            ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository).save(saved.capture());
            // Moving the deadline produces a new key, which is correct: that is a
            // different commitment and worth mentioning again.
            assertThat(saved.getValue().getDedupeKey())
                    .isEqualTo("goal-deadline:goal-1:" + goal.getDeadline());
        }

        @Test
        @DisplayName("says 'due today' rather than 'due in 0 days'")
        void dueTodayReadsProperly() {
            when(goalRepository.findDueBetween(any(), any())).thenReturn(List.of(dueIn(0)));

            notificationService.warnAboutApproachingDeadlines();

            ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
            verify(notificationRepository).save(saved.capture());
            assertThat(saved.getValue().getTitle()).isEqualTo("A goal is due today");
        }
    }

    @Test
    @DisplayName("Read notifications are swept once they are old — the feed is news, not an archive")
    void purgesOldReadNotifications() {
        when(notificationRepository.deleteReadOlderThan(any())).thenReturn(7);

        notificationService.purgeOldReadNotifications();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(notificationRepository).deleteReadOlderThan(cutoff.capture());
        // Without this the table only ever grows — the same slow leak the rate limiter's
        // bucket map had before it started evicting.
        assertThat(cutoff.getValue()).isBefore(LocalDateTime.now().minusDays(59));
    }
}
