package com.project.fitness.service;

import com.project.fitness.model.*;
import com.project.fitness.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RecommendationService — Feature 10
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecommendationService Tests")
class RecommendationServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private ActivityRepository activityRepository;
    @Mock private RecommendationRepository recommendationRepository;
    @Mock private GeminiService geminiService;
    @InjectMocks private RecommendationService recommendationService;

    private User user;
    private Activity activity;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .id("user-1")
                .firstName("Nikhil")
                .age(25)
                .weight(75.0)
                .currentStreak(5)
                .build();

        activity = new Activity();
        activity.setId("activity-1");
        activity.setUser(user);
        activity.setType(ActivityType.RUNNING);
        activity.setDuration(30);
        activity.setCaloriesBurned(350);
    }

    // ── Gemini disabled: fallback recommendation generated ───────────────────

    @Test
    @DisplayName("Gemini not configured: fallback rule-based recommendation is saved")
    void geminiDisabled_fallbackRecommendationSaved() {
        when(activityRepository.findTop5ByUserIdOrderByStartTimeDesc("user-1")).thenReturn(List.of(activity));
        when(geminiService.generateRecommendation(any())).thenReturn(null); // no API key
        when(recommendationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Recommendation result = recommendationService.generateRecommendation(user, activity);

        assertThat(result).isNotNull();
        assertThat(result.getType()).isEqualTo("MOTIVATION");
        assertThat(result.getRecommendation()).isNotBlank();
        assertThat(result.getImprovements()).isNotEmpty();
        verify(recommendationRepository).save(any(Recommendation.class));
    }

    // ── Auto-trigger every 3rd activity ──────────────────────────────────────

    @Test
    @DisplayName("3rd activity: recommendation is auto-generated")
    void thirdActivity_triggersRecommendation() {
        Activity a1 = new Activity(); a1.setUser(user);
        Activity a2 = new Activity(); a2.setUser(user);
        Activity a3 = activity;

        // 3 activities total → triggers recommendation
        when(activityRepository.countByUserId("user-1")).thenReturn(3L);
        when(activityRepository.findTop5ByUserIdOrderByStartTimeDesc("user-1"))
                .thenReturn(List.of(a3, a2, a1));
        when(geminiService.generateRecommendation(any())).thenReturn(null);
        when(recommendationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        recommendationService.maybeGenerateAfterActivity(user, a3);

        verify(recommendationRepository).save(any());
    }

    @Test
    @DisplayName("2nd activity: recommendation is NOT triggered")
    void secondActivity_doesNotTrigger() {
        Activity a1 = new Activity(); a1.setUser(user);
        Activity a2 = activity;

        when(activityRepository.countByUserId("user-1")).thenReturn(2L);

        recommendationService.maybeGenerateAfterActivity(user, a2);

        verify(recommendationRepository, never()).save(any());
    }

    // ── Gemini response parsed correctly ─────────────────────────────────────

    @Test
    @DisplayName("Gemini returns valid JSON: recommendation uses AI response fields")
    void geminiEnabled_aiResponseUsed() {
        when(activityRepository.findTop5ByUserIdOrderByStartTimeDesc("user-1")).thenReturn(List.of(activity));
        when(geminiService.generateRecommendation(any())).thenReturn(java.util.Map.of(
                "type", "PERFORMANCE",
                "recommendation", "Try interval training to break your plateau.",
                "improvements", List.of("Add speed work", "Increase distance"),
                "suggestions", List.of("Hydrate well", "Sleep 8 hours"),
                "safety", List.of()
        ));
        when(recommendationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Recommendation result = recommendationService.generateRecommendation(user, activity);

        assertThat(result.getType()).isEqualTo("PERFORMANCE");
        assertThat(result.getRecommendation()).contains("interval training");
        assertThat(result.getImprovements()).hasSize(2);
        assertThat(result.getSuggestions()).hasSize(2);
    }

    // ── Empty activity history ────────────────────────────────────────────────

    @Test
    @DisplayName("No past activities: fallback recommendation still generated")
    void noHistory_fallbackStillGenerated() {
        when(activityRepository.findTop5ByUserIdOrderByStartTimeDesc("user-1")).thenReturn(List.of());
        when(geminiService.generateRecommendation(any())).thenReturn(null);
        when(recommendationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Recommendation result = recommendationService.generateRecommendation(user, activity);

        assertThat(result).isNotNull();
        assertThat(result.getRecommendation()).isNotBlank();
    }
}
