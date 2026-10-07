package com.project.fitness.service;

import com.project.fitness.dto.ActivityRequest;
import com.project.fitness.model.Activity;
import com.project.fitness.model.ActivityType;
import com.project.fitness.model.User;
import com.project.fitness.repository.ActivityRepository;
import com.project.fitness.repository.PainLogRepository;
import com.project.fitness.repository.PersonalBestRepository;
import com.project.fitness.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * What an activity edit does to {@code caloriesBurned} (PLAN P4-2).
 *
 * <p>The edit used to recalculate whenever calories were omitted, so a notes-only edit
 * replaced the figure the user had typed, and nulled it when the profile had no weight.
 * The calculator is stubbed as 10 kcal a minute so a calculated figure is easy to spot.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActivityService — calories on edit")
class ActivityServiceEditCaloriesTest {

    @Mock private ActivityRepository activityRepository;
    @Mock private UserRepository userRepository;
    @Mock private CalorieCalculatorService calorieCalculatorService;
    @Mock private StreakService streakService;
    @Mock private PersonalBestService personalBestService;
    @Mock private MuscleRecoveryService muscleRecoveryService;
    @Mock private ComebackService comebackService;
    @Mock private GoalService goalService;
    @Mock private RecommendationService recommendationService;
    @Mock private PersonalBestRepository personalBestRepository;
    @Mock private PainLogRepository painLogRepository;
    @Mock private ProgressionService progressionService;
    @Mock private ActivityRecalculationService recalculationService;

    @InjectMocks private ActivityService activityService;

    private User owner;
    private Activity activity;

    @BeforeEach
    void setUp() {
        owner = User.builder().id("owner-1").weight(75.0).currentStreak(1).longestStreak(1).build();
        activity = Activity.builder()
                .id("act-1").user(owner).type(ActivityType.RUNNING).duration(30).build();

        when(activityRepository.findById("act-1")).thenReturn(Optional.of(activity));
        when(activityRepository.save(any(Activity.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(calorieCalculatorService.calculate(any(), anyInt(), anyDouble()))
                .thenAnswer(i -> 10 * (int) i.getArgument(1));
    }

    private Integer edit(ActivityRequest request) {
        activityService.updateActivity("owner-1", "act-1", request);
        return activity.getCaloriesBurned();
    }

    private static ActivityRequest notesOnly() {
        ActivityRequest r = new ActivityRequest();
        r.setNotes("felt good");
        return r;
    }

    private static ActivityRequest duration(int minutes) {
        ActivityRequest r = new ActivityRequest();
        r.setDuration(minutes);
        return r;
    }

    @Test
    @DisplayName("a notes-only edit keeps a typed figure")
    void notesOnlyEdit_keepsTypedCalories() {
        activity.setCaloriesBurned(999);
        assertThat(edit(notesOnly())).isEqualTo(999);
    }

    @Test
    @DisplayName("a notes-only edit with no weight on the profile doesn't null the figure")
    void notesOnlyEdit_withoutWeight_keepsCalories() {
        owner.setWeight(null);
        activity.setCaloriesBurned(420);
        assertThat(edit(notesOnly())).isEqualTo(420);
    }

    @Test
    @DisplayName("a duration change re-derives a figure that was calculated")
    void durationChange_recalculatesCalculatedFigure() {
        activity.setCaloriesBurned(300);            // 30 min × 10: what the calculator gave
        assertThat(edit(duration(45))).isEqualTo(450);
    }

    @Test
    @DisplayName("a type change re-derives a figure that was calculated")
    void typeChange_recalculatesCalculatedFigure() {
        activity.setCaloriesBurned(300);
        when(calorieCalculatorService.calculate(eq(ActivityType.WALKING), anyInt(), anyDouble()))
                .thenReturn(120);

        ActivityRequest r = new ActivityRequest();
        r.setType(ActivityType.WALKING);
        assertThat(edit(r)).isEqualTo(120);
    }

    @Test
    @DisplayName("a duration change keeps a figure the user typed")
    void durationChange_keepsTypedFigure() {
        activity.setCaloriesBurned(999);
        assertThat(edit(duration(45))).isEqualTo(999);
    }

    @Test
    @DisplayName("a duration change fills in a missing figure")
    void durationChange_fillsMissingFigure() {
        assertThat(edit(duration(45))).isEqualTo(450);
    }

    @Test
    @DisplayName("resending the same duration is not a change")
    void sameDuration_isNotAChange() {
        activity.setCaloriesBurned(999);
        assertThat(edit(duration(30))).isEqualTo(999);
    }

    @Test
    @DisplayName("a figure sent with the edit is stored as-is")
    void explicitFigure_wins() {
        activity.setCaloriesBurned(300);
        ActivityRequest r = duration(45);
        r.setCaloriesBurned(512);
        r.setRecalculateCalories(true);              // ignored: the explicit figure wins
        assertThat(edit(r)).isEqualTo(512);
    }

    @Test
    @DisplayName("recalculateCalories replaces a typed figure on request")
    void recalculateFlag_replacesTypedFigure() {
        activity.setCaloriesBurned(999);
        ActivityRequest r = notesOnly();
        r.setRecalculateCalories(true);
        assertThat(edit(r)).isEqualTo(300);
    }

    @Test
    @DisplayName("recalculateCalories with no weight keeps the stored figure")
    void recalculateFlag_withoutWeight_keepsFigure() {
        owner.setWeight(null);
        activity.setCaloriesBurned(999);
        ActivityRequest r = notesOnly();
        r.setRecalculateCalories(true);
        assertThat(edit(r)).isEqualTo(999);
        verify(calorieCalculatorService, never()).calculate(any(), anyInt(), anyDouble());
    }
}
