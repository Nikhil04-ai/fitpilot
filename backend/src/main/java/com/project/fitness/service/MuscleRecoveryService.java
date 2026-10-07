package com.project.fitness.service;

import com.project.fitness.model.Activity;
import com.project.fitness.model.ActivityType;
import com.project.fitness.model.MuscleGroup;
import com.project.fitness.model.MuscleRecovery;
import com.project.fitness.model.User;
import com.project.fitness.repository.MuscleRecoveryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Feature 4 — Muscle Recovery Tracker
 * Updates muscle group timestamps after activity and warns when overtraining risk exists.
 */
@Service
@RequiredArgsConstructor
public class MuscleRecoveryService {

    private final MuscleRecoveryRepository muscleRecoveryRepository;

    // Muscle groups worked by each activity type
    private static final Map<ActivityType, List<MuscleGroup>> MUSCLE_MAP = Map.of(
            ActivityType.RUNNING,         List.of(MuscleGroup.QUADRICEPS, MuscleGroup.HAMSTRINGS, MuscleGroup.CALVES, MuscleGroup.CORE),
            ActivityType.WALKING,         List.of(MuscleGroup.QUADRICEPS, MuscleGroup.CALVES),
            ActivityType.CYCLING,         List.of(MuscleGroup.QUADRICEPS, MuscleGroup.HAMSTRINGS, MuscleGroup.GLUTES, MuscleGroup.CALVES),
            ActivityType.SWIMMING,        List.of(MuscleGroup.SHOULDERS, MuscleGroup.BACK, MuscleGroup.CORE, MuscleGroup.ARMS, MuscleGroup.LEGS),
            ActivityType.WEIGHT_TRAINING, List.of(MuscleGroup.FULL_BODY),
            ActivityType.YOGA,            List.of(MuscleGroup.FULL_BODY),
            ActivityType.HIIT,            List.of(MuscleGroup.FULL_BODY),
            ActivityType.CARDIO,          List.of(MuscleGroup.CORE, MuscleGroup.LEGS),
            ActivityType.STRETCHING,      List.of(MuscleGroup.FULL_BODY),
            ActivityType.OTHER,           List.of(MuscleGroup.FULL_BODY)
    );

    /**
     * The muscle groups an activity type works. Exposed because recalculation after an
     * edit or delete (ActivityRecalculationService) has to walk the same mapping
     * backwards, and a second copy of this table would drift from this one.
     */
    public List<MuscleGroup> musclesFor(ActivityType type) {
        return MUSCLE_MAP.getOrDefault(type, List.of(MuscleGroup.FULL_BODY));
    }

    /**
     * Returns a list of overtraining warning messages (empty if all muscle groups are recovered).
     * Also updates lastWorkedAt for all affected muscle groups.
     */
    public List<String> processActivity(User user, Activity activity) {
        List<MuscleGroup> groups = musclesFor(activity.getType());
        List<String> warnings = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (MuscleGroup group : groups) {
            Optional<MuscleRecovery> existing =
                    muscleRecoveryRepository.findByUserIdAndMuscleGroup(user.getId(), group);

            if (existing.isPresent()) {
                MuscleRecovery recovery = existing.get();
                // lastWorkedAt is nullable in the schema, and ChronoUnit.between(null, ..)
                // throws — which would abort the whole activity save, from a getter.
                long hoursSinceLastWork = recovery.getLastWorkedAt() != null
                        ? ChronoUnit.HOURS.between(recovery.getLastWorkedAt(), now)
                        : Long.MAX_VALUE;   // never worked → fully recovered

                if (hoursSinceLastWork < recovery.getRecommendedRestHours()) {
                    long hoursRemaining = recovery.getRecommendedRestHours() - hoursSinceLastWork;
                    warnings.add(group.name() + " needs " + hoursRemaining + " more hours of rest.");
                }

                recovery.setLastWorkedAt(now);
                muscleRecoveryRepository.save(recovery);
            } else {
                // First time this muscle group is worked
                MuscleRecovery recovery = MuscleRecovery.builder()
                        .user(user)
                        .muscleGroup(group)
                        .lastWorkedAt(now)
                        .build();
                muscleRecoveryRepository.save(recovery);
            }
        }

        return warnings;
    }

    public List<MuscleRecovery> getRecoveryStatus(String userId) {
        return muscleRecoveryRepository.findByUserId(userId);
    }
}
