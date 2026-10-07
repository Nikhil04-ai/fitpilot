package com.project.fitness.service;

import com.project.fitness.exceptions.AccountNotFoundException;
import com.project.fitness.model.Activity;
import com.project.fitness.model.BodyPart;
import com.project.fitness.model.PainLog;
import com.project.fitness.model.User;
import com.project.fitness.repository.ActivityRepository;
import com.project.fitness.repository.PainLogRepository;
import com.project.fitness.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Feature 6 — Pain Point Logger
 */
@Transactional
@Service
@RequiredArgsConstructor
public class PainAnalysisService {

    private final PainLogRepository painLogRepository;
    private final UserRepository userRepository;
    private final ActivityRepository activityRepository;

    public PainLog logPain(String userId, String activityId, BodyPart bodyPart,
                           int severity, String description) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AccountNotFoundException(userId));

        // An entry with no body part was accepted, then crashed pattern detection for
        // that user from then on (PLAN P4-7).
        if (bodyPart == null) {
            throw new RuntimeException("Pick the body part that hurts.");
        }
        InputChecks.painSeverity(severity);

        // Ownership-checked (PLAN P4-6). This used to accept any id: someone else's
        // activity got linked, and an unknown one was silently dropped to null. Same
        // messages as ActivityService, so a client sees one contract for activity ids.
        Activity activity = null;
        if (activityId != null && !activityId.isBlank()) {
            activity = activityRepository.findById(activityId)
                    .orElseThrow(() -> new RuntimeException("Activity not found: " + activityId));
            if (!activity.getUser().getId().equals(userId)) {
                throw new AccessDeniedException("You don't have access to this activity.");
            }
        }

        PainLog log = PainLog.builder()
                .user(user)
                .activity(activity)
                .bodyPart(bodyPart)
                .severity(severity)
                .description(description)
                .build();

        return painLogRepository.save(log);
    }

    /** One page of pain history. Unbounded, this grew with every entry forever (PLAN P3-3). */
    public Page<PainLog> getPainHistory(String userId, Pageable pageable) {
        return painLogRepository.findByUserId(userId, pageable);
    }

    /**
     * Returns pattern alerts if any body part has been reported 3+ times in 21 days.
     */
    public List<String> detectPatterns(String userId) {
        LocalDateTime twentyOneDaysAgo = LocalDateTime.now().minusDays(21);
        List<PainLog> recentLogs = painLogRepository.findByUserId(userId).stream()
                .filter(p -> p.getLoggedAt() != null && p.getLoggedAt().isAfter(twentyOneDaysAgo))
                // groupingBy rejects a null key; rows saved before bodyPart was
                // required can have one, and they'd take the whole endpoint down.
                .filter(p -> p.getBodyPart() != null)
                .collect(Collectors.toList());

        // Group by body part
        Map<BodyPart, List<PainLog>> byBodyPart = recentLogs.stream()
                .collect(Collectors.groupingBy(PainLog::getBodyPart));

        List<String> alerts = new ArrayList<>();
        for (Map.Entry<BodyPart, List<PainLog>> entry : byBodyPart.entrySet()) {
            if (entry.getValue().size() >= 3) {
                String partName = entry.getKey().name().replace("_", " ").toLowerCase();
                alerts.add(String.format(
                        "You've reported %s discomfort %d times in 3 weeks. Consider resting this area and consulting a professional.",
                        partName, entry.getValue().size()));
            }
        }

        return alerts;
    }
}
