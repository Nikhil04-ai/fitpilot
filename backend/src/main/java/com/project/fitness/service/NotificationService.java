package com.project.fitness.service;

import com.project.fitness.exceptions.AccountNotFoundException;
import com.project.fitness.model.Goal;
import com.project.fitness.model.Notification;
import com.project.fitness.model.NotificationType;
import com.project.fitness.model.User;
import com.project.fitness.repository.GoalRepository;
import com.project.fitness.repository.NotificationRepository;
import com.project.fitness.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The delivery layer (PLAN P2-2).
 *
 * <p>The app computed things on a schedule and told nobody. A plateau scan ran every
 * Monday at 09:00, challenge scores updated at 23:55 and settled at 00:05 — and every
 * one of those was visible only if the user happened to open the right panel on the
 * right day. Work nobody is told about may as well not have run.
 *
 * <p><b>Producers call {@link #notify}, which is deliberately quiet.</b> It never throws
 * into its caller: a notification is a side effect of something more important (settling
 * a challenge, writing a digest), and failing to announce a result must not roll back
 * the result itself.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final GoalRepository goalRepository;

    /** A goal is worth mentioning once it is this close and still unmet. */
    private static final int DEADLINE_WARNING_DAYS = 7;

    /** Read notifications are swept after this long — the feed is news, not an archive. */
    private static final int KEEP_READ_DAYS = 60;

    // ── Producing ────────────────────────────────────────────────────────────

    /**
     * Records something worth telling the user.
     *
     * <p>Returns quietly if {@code dedupeKey} has already been used for this user, which
     * is what stops a daily job re-announcing the same goal deadline every morning until
     * it passes.
     */
    @Transactional
    public Notification notify(User user, NotificationType type, String title, String body,
                               String targetPanel, String dedupeKey) {
        try {
            if (dedupeKey != null && notificationRepository.existsByUserIdAndDedupeKey(user.getId(), dedupeKey)) {
                return null;
            }
            return notificationRepository.save(Notification.builder()
                    .user(user)
                    .type(type)
                    .title(title)
                    .body(trim(body))
                    .targetPanel(targetPanel)
                    .dedupeKey(dedupeKey)
                    .build());
        } catch (Exception e) {
            // Never propagate. The caller has just settled a challenge or written a
            // digest; losing the announcement is bad, losing the result is worse.
            log.warn("Could not record {} notification for user {}: {}", type, user.getId(), e.toString());
            return null;
        }
    }

    // ── Reading ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> getFeed(String userId, Pageable pageable) {
        userRepository.findById(userId).orElseThrow(() -> new AccountNotFoundException(userId));
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(this::toMap);
    }

    /** Just the number, for the badge. */
    @Transactional(readOnly = true)
    public long unreadCount(String userId) {
        return notificationRepository.countByUserIdAndReadAtIsNull(userId);
    }

    // ── Updating ─────────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> markRead(String requestingUserId, String notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new RuntimeException("Notification not found: " + notificationId));
        // A notification id is all the caller sends — without this anyone could read
        // (and so silence) anyone else's feed (AGENT.md §2).
        if (!notification.getUser().getId().equals(requestingUserId)) {
            throw new AccessDeniedException("You don't have access to this notification.");
        }
        if (notification.getReadAt() == null) {
            notification.setReadAt(LocalDateTime.now());
            notificationRepository.save(notification);
        }
        return toMap(notification);
    }

    @Transactional
    public int markAllRead(String userId) {
        return notificationRepository.markAllRead(userId, LocalDateTime.now());
    }

    // ── Scheduled producers ──────────────────────────────────────────────────

    /**
     * Warns about goals whose deadline is close and which are not met yet.
     *
     * <p>Runs daily; the dedupe key includes the goal and its deadline, so each goal is
     * mentioned once rather than every morning for a week. Moving the deadline produces
     * a new key, which is correct — that is a different commitment.
     */
    @Scheduled(cron = "0 0 9 * * *")
    @Transactional
    public void warnAboutApproachingDeadlines() {
        LocalDate today = LocalDate.now();
        int raised = 0;

        // Queried by window rather than scanned: findAll() would load every goal in the
        // database each morning to find the handful actually due (AGENT.md §9).
        for (Goal goal : goalRepository.findDueBetween(today, today.plusDays(DEADLINE_WARNING_DAYS))) {
            LocalDate deadline = goal.getDeadline();
            long daysLeft = ChronoUnit.DAYS.between(today, deadline);

            User user = goal.getUser();
            if (user == null) continue;

            String label = goal.getType() != null ? goal.getType().name().replace('_', ' ') : "goal";
            String unit = goal.getType() != null ? goal.getType().unit() : "";
            notify(user, NotificationType.GOAL_DEADLINE,
                    daysLeft == 0 ? "A goal is due today" : "A goal is due in " + daysLeft + " day"
                            + (daysLeft == 1 ? "" : "s"),
                    label + ": " + trimNumber(goal.getCurrentValue()) + " of "
                            + trimNumber(goal.getTargetValue()) + " " + unit + " so far.",
                    "goals",
                    "goal-deadline:" + goal.getId() + ":" + deadline);
            raised++;
        }

        if (raised > 0) log.info("Deadline warnings: raised {}", raised);
    }

    /**
     * Drops read notifications once they are old.
     *
     * <p>The feed is news. Without this the table only ever grows, which is the same
     * slow leak the rate limiter's bucket map had before it started evicting.
     */
    @Scheduled(cron = "0 45 3 * * *")
    @Transactional
    public void purgeOldReadNotifications() {
        int removed = notificationRepository.deleteReadOlderThan(
                LocalDateTime.now().minusDays(KEEP_READ_DAYS));
        if (removed > 0) log.info("Purged {} read notification(s) older than {} days", removed, KEEP_READ_DAYS);
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    private Map<String, Object> toMap(Notification n) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", n.getId());
        map.put("type", n.getType() != null ? n.getType().name() : NotificationType.GENERAL.name());
        map.put("title", n.getTitle());
        map.put("body", n.getBody());
        map.put("targetPanel", n.getTargetPanel());
        map.put("isRead", n.getReadAt() != null);
        map.put("readAt", n.getReadAt());
        map.put("createdAt", n.getCreatedAt());
        return map;
    }

    private String trim(String body) {
        if (body == null) return null;
        String t = body.trim();
        return t.length() > 1000 ? t.substring(0, 1000) : t;
    }

    private String trimNumber(Double value) {
        if (value == null) return "0";
        return value == Math.floor(value) ? String.valueOf((long) (double) value) : String.valueOf(value);
    }
}
