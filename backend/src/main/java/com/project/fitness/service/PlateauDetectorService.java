package com.project.fitness.service;

import com.project.fitness.model.Activity;
import com.project.fitness.model.ActivityType;
import com.project.fitness.model.NotificationType;
import com.project.fitness.model.User;
import com.project.fitness.repository.ActivityRepository;
import com.project.fitness.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Feature 7 — Plateau Detector
 * Compares last 2 weeks vs previous 2 weeks for the same activity type.
 * Flags a plateau if calorie output has not improved by more than 5%.
 *
 * <p>The weekly scan below used to be a stub that logged a line and returned. It now
 * runs, and what it finds reaches the user through the notification feed (PLAN P2-2)
 * rather than waiting for them to open the right panel on the right day.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlateauDetectorService {

    private final ActivityRepository activityRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    /**
     * Checks if a user has plateaued in a specific activity type.
     * Returns a plateau result map (empty if not enough data or no plateau).
     */
    public Map<String, Object> checkPlateau(String userId, ActivityType activityType) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime twoWeeksAgo = now.minusDays(14);
        LocalDateTime fourWeeksAgo = now.minusDays(28);

        List<Activity> recentActivities = activityRepository
                .findByUserIdAndStartTimeBetween(userId, twoWeeksAgo, now)
                .stream().filter(a -> a.getType() == activityType).collect(Collectors.toList());

        List<Activity> previousActivities = activityRepository
                .findByUserIdAndStartTimeBetween(userId, fourWeeksAgo, twoWeeksAgo)
                .stream().filter(a -> a.getType() == activityType).collect(Collectors.toList());

        if (recentActivities.size() < 2 || previousActivities.size() < 2) {
            return Map.of("status", "insufficient_data",
                          "message", "Not enough activities to detect a plateau yet.");
        }

        OptionalDouble recentAvg = recentActivities.stream()
                .filter(a -> a.getCaloriesBurned() != null)
                .mapToInt(Activity::getCaloriesBurned).average();

        OptionalDouble previousAvg = previousActivities.stream()
                .filter(a -> a.getCaloriesBurned() != null)
                .mapToInt(Activity::getCaloriesBurned).average();

        if (recentAvg.isEmpty() || previousAvg.isEmpty()) {
            return Map.of("status", "insufficient_data", "message", "No calorie data to compare.");
        }

        double improvement = ((recentAvg.getAsDouble() - previousAvg.getAsDouble()) / previousAvg.getAsDouble()) * 100;
        boolean isPlateaued = improvement <= 5.0;

        Map<String, Object> result = new HashMap<>();
        result.put("activityType", activityType.name());
        result.put("recentAverageCalories", String.format("%.1f", recentAvg.getAsDouble()));
        result.put("previousAverageCalories", String.format("%.1f", previousAvg.getAsDouble()));
        result.put("improvementPercent", String.format("%.1f%%", improvement));
        result.put("plateauDetected", isPlateaued);

        if (isPlateaued) {
            result.put("recommendation",
                    "Your " + activityType.name().toLowerCase() + " performance has plateaued. " +
                    "Try increasing intensity, duration, or switching workout styles to break through.");
        }

        return result;
    }

    /**
     * The weekly scan, Monday at 09:00.
     *
     * <p><b>This used to do nothing.</b> It was a log line and a comment reading "full
     * user-wide scan would be added here", so the feature that PROGRESS.md listed as a
     * scheduled plateau detector consisted of an endpoint nobody called on a schedule
     * that produced no output. It now actually scans, and — the other half of the same
     * problem — tells the user what it found, through the notification feed
     * (PLAN P2-2).
     *
     * <p>Scoped to users who trained in the last four weeks, which is the window
     * {@link #checkPlateau} compares over: nobody outside it can have a plateau to
     * detect, so scanning them is work with no possible result.
     */
    @Scheduled(cron = "0 0 9 * * MON")
    @Transactional
    public void weeklyPlateauScan() {
        LocalDateTime fourWeeksAgo = LocalDateTime.now().minusDays(28);
        Set<String> userIds = new LinkedHashSet<>(
                activityRepository.findUserIdsWithActivitySince(fourWeeksAgo));

        log.info("Weekly plateau scan: {} user(s) trained in the last 4 weeks", userIds.size());
        int flagged = 0;

        for (String userId : userIds) {
            Optional<User> user = userRepository.findById(userId);
            if (user.isEmpty()) continue;

            for (ActivityType type : typesTrainedSince(userId, fourWeeksAgo)) {
                try {
                    Map<String, Object> result = checkPlateau(userId, type);
                    if (!Boolean.TRUE.equals(result.get("plateauDetected"))) continue;

                    notificationService.notify(user.get(), NotificationType.PLATEAU,
                            "Your " + type.name().toLowerCase().replace('_', ' ') + " has plateaued",
                            String.valueOf(result.get("recommendation")),
                            "insights",
                            // Once a week per activity type, not once a scan: the same
                            // plateau is still a plateau next Monday, and a feed that
                            // repeats itself is one people stop reading.
                            "plateau:" + type.name() + ":" + LocalDate.now());
                    flagged++;
                } catch (Exception e) {
                    log.warn("Plateau scan failed for user {} / {}: {}", userId, type, e.toString());
                }
            }
        }

        log.info("Weekly plateau scan complete — {} plateau(s) flagged", flagged);
    }

    /** Only the types a user actually trains: checking the other nine is wasted work. */
    private Set<ActivityType> typesTrainedSince(String userId, LocalDateTime since) {
        return activityRepository.findByUserIdAndStartTimeBetween(userId, since, LocalDateTime.now())
                .stream()
                .map(Activity::getType)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
