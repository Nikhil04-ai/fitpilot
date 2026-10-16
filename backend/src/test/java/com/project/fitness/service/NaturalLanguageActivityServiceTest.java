package com.project.fitness.service;

import com.project.fitness.dto.ActivityRequest;
import com.project.fitness.dto.ActivityResponse;
import com.project.fitness.model.ActivityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * NaturalLanguageActivityService — what gets saved from a free-text description.
 *
 * <p>The live QA pass found that a blank, missing or nonsense description was saved as
 * an empty "OTHER" workout with a 200, after spending a Gemini call on it.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NaturalLanguageActivityService")
class NaturalLanguageActivityServiceTest {

    @Mock private GeminiService geminiService;
    @Mock private ActivityService activityService;
    @InjectMocks private NaturalLanguageActivityService service;

    @Test
    @DisplayName("a blank or missing description is refused before the model is asked")
    void blankDescriptionIsRefused() {
        for (String blank : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> service.parseAndSave("u-1", blank)).hasMessageContaining("Describe the workout");
        }
        verifyNoInteractions(geminiService, activityService);
    }

    @Test
    @DisplayName("text with no workout in it saves nothing")
    void nothingParsedSavesNothing() {
        when(geminiService.parseNaturalLanguageActivity(anyString()))
                .thenReturn(Map.of("type", "OTHER", "additionalMetrics", Map.of()));

        assertThatThrownBy(() -> service.parseAndSave("u-1", "asdkjh qwe zzz"))
                .hasMessageContaining("Couldn't find a workout");
        verifyNoInteractions(activityService);
    }

    /** What the live model actually returned for "asdkjh qwe zzz": the text echoed back, no numbers. */
    @Test
    @DisplayName("metrics with no numbers in them don't count as a workout")
    void echoedTextIsNotAWorkout() {
        java.util.Map<String, Object> metrics = new java.util.HashMap<>();
        metrics.put("notes", "asdkjh qwe zzz");
        metrics.put("distance_km", null);
        when(geminiService.parseNaturalLanguageActivity(anyString()))
                .thenReturn(Map.of("type", "OTHER", "additionalMetrics", metrics));

        assertThatThrownBy(() -> service.parseAndSave("u-1", "asdkjh qwe zzz"))
                .hasMessageContaining("Couldn't find a workout");
        verifyNoInteractions(activityService);
    }

    @Test
    @DisplayName("a real description is saved, including a distance the model put in additionalMetrics")
    void realDescriptionIsSaved() {
        when(geminiService.parseNaturalLanguageActivity(anyString()))
                .thenReturn(Map.of("type", "RUNNING", "additionalMetrics", Map.of("distance_km", 5)));
        when(activityService.trackActivity(any())).thenReturn(new ActivityResponse());

        service.parseAndSave("u-1", "ran 5k");

        ArgumentCaptor<ActivityRequest> saved = ArgumentCaptor.forClass(ActivityRequest.class);
        verify(activityService).trackActivity(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo(ActivityType.RUNNING);
        assertThat(saved.getValue().getUserId()).isEqualTo("u-1");
    }

    @Test
    @DisplayName("a workout with only movements is still saved")
    void movementsAloneAreEnough() {
        when(geminiService.parseNaturalLanguageActivity(anyString())).thenReturn(Map.of(
                "type", "WEIGHT_TRAINING",
                "exercises", List.of(Map.of("name", "Bench Press", "sets", 3, "reps", 8, "weightKg", 60))));
        when(activityService.trackActivity(any())).thenReturn(new ActivityResponse());

        service.parseAndSave("u-1", "bench 3x8 at 60");

        verify(activityService).trackActivity(any());
    }
}
