package com.project.fitness.service;

import com.project.fitness.model.Activity;
import com.project.fitness.model.Recommendation;
import com.project.fitness.model.User;
import com.project.fitness.repository.ActivityRepository;
import com.project.fitness.repository.RecommendationRepository;
import com.project.fitness.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Feature 10 — AI-Powered Recommendations (rewritten to use Gemini)
 *
 * Auto-triggers every 3rd activity save.
 * Falls back to a basic rule-based recommendation if Gemini is not configured.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationService {

    private final UserRepository userRepository;
    private final ActivityRepository activityRepository;
    private final RecommendationRepository recommendationRepository;
    private final GeminiService geminiService;

    /**
     * Called automatically after every activity save.
     * Only generates a recommendation on the 3rd, 6th, 9th... activity.
     */
    public void maybeGenerateAfterActivity(User user, Activity latestActivity) {
        // Counted in SQL. This is called from ActivityService on every save, and the
        // previous version loaded the user's entire activity history into memory just
        // to call .size() on it — so logging a workout got steadily slower the longer
        // someone used the app.
        long activityCount = activityRepository.countByUserId(user.getId());
        if (activityCount % 3 != 0) return;

        generateRecommendation(user, latestActivity);
    }

    @Transactional
    public Recommendation generateRecommendation(User user, Activity triggeringActivity) {
        // Ordered and limited by the database — same reason as above.
        List<Activity> last5 = activityRepository.findTop5ByUserIdOrderByStartTimeDesc(user.getId());

        // Build readable activity list for Gemini
        String activityList = last5.stream()
                .map(a -> String.format("%s for %d min (%s cal)",
                        a.getType() != null ? a.getType().name() : "UNKNOWN",
                        a.getDuration() != null ? a.getDuration() : 0,
                        a.getCaloriesBurned() != null ? a.getCaloriesBurned() : "?"))
                .collect(Collectors.joining(", "));

        String prompt = """
                You are a certified fitness coach. Analyze this user's recent activity data and provide recommendations.

                User Profile: %s years old, %skg
                Recent Activities (last 5): %s
                Current Streak: %d days

                Respond in this exact JSON format:
                {
                  "type": "PERFORMANCE|RECOVERY|SAFETY|MOTIVATION",
                  "recommendation": "Main recommendation in 2-3 sentences",
                  "improvements": ["specific improvement 1", "specific improvement 2"],
                  "suggestions": ["suggestion 1", "suggestion 2", "suggestion 3"],
                  "safety": ["safety tip 1"] or []
                }
                """.formatted(
                        user.getAge() != null ? user.getAge() : "unknown",
                        user.getWeight() != null ? user.getWeight() : "unknown",
                        activityList,
                        user.getCurrentStreak() != null ? user.getCurrentStreak() : 0
                );

        Map<String, Object> aiResponse = geminiService.generateRecommendation(prompt);

        Recommendation recommendation = new Recommendation();
        recommendation.setUser(user);
        recommendation.setActivity(triggeringActivity);

        if (aiResponse != null) {
            recommendation.setType(String.valueOf(aiResponse.getOrDefault("type", "PERFORMANCE")));
            recommendation.setRecommendation(String.valueOf(aiResponse.getOrDefault("recommendation", "")));

            Object improvements = aiResponse.get("improvements");
            if (improvements instanceof List) {
                recommendation.setImprovements((List<String>) improvements);
            }

            Object suggestions = aiResponse.get("suggestions");
            if (suggestions instanceof List) {
                recommendation.setSuggestions((List<String>) suggestions);
            }

            Object safety = aiResponse.get("safety");
            if (safety instanceof List) {
                recommendation.setSafety((List<String>) safety);
            }

        } else {
            // Gemini not configured — rule-based fallback
            recommendation.setType("MOTIVATION");
            recommendation.setRecommendation(
                    "Keep going! You're on a " + user.getCurrentStreak() + "-day streak. " +
                    "Consistency is the most important factor in fitness progress.");
            recommendation.setImprovements(List.of(
                    "Try to vary your workout types to prevent overuse injuries.",
                    "Ensure you're getting 7-9 hours of sleep for optimal recovery."));
            recommendation.setSuggestions(List.of(
                    "Add a rest day if you've worked out 4+ days in a row.",
                    "Stay hydrated — aim for 2-3 litres of water per day.",
                    "Log your meals to better understand your energy levels."));
            recommendation.setSafety(List.of());
        }

        return recommendationRepository.save(recommendation);
    }

    @Transactional(readOnly = true)
    public List<Recommendation> getUserRecommendation(String userId) {
        return recommendationRepository.findByUserId(userId);
    }

    @Transactional(readOnly = true)
    public List<Recommendation> getActivityRecommendation(String requestingUserId, String activityId) {
        Activity activity = activityRepository.findById(activityId)
                .orElseThrow(() -> new RuntimeException("Activity not found: " + activityId));
        if (!activity.getUser().getId().equals(requestingUserId)) {
            throw new AccessDeniedException("You don't have access to this activity.");
        }
        return recommendationRepository.findByActivityId(activityId);
    }
}
