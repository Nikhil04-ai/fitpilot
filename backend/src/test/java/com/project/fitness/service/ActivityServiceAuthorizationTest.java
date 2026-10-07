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
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * An activity is addressed by id alone, so ownership has to be checked in the
 * service — otherwise knowing (or guessing) an id is enough to rate or rewrite
 * someone else's workout.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActivityService Authorization Tests")
class ActivityServiceAuthorizationTest {

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

    private Activity othersActivity;
    private User owner;

    @BeforeEach
    void setUp() {
        owner = User.builder().id("owner-1").currentStreak(1).longestStreak(1).build();
        othersActivity = Activity.builder()
                .id("act-1").user(owner).type(ActivityType.RUNNING).duration(30).build();
    }

    @Test
    @DisplayName("rateActivity rejects a rating on another user's activity")
    void rateActivity_deniesNonOwner() {
        when(activityRepository.findById("act-1")).thenReturn(Optional.of(othersActivity));

        assertThatThrownBy(() -> activityService.rateActivity("attacker-9", "act-1", 5))
                .isInstanceOf(AccessDeniedException.class);

        verify(activityRepository, never()).save(any());
        assertThat(othersActivity.getDifficultyRating()).isNull();
    }

    @Test
    @DisplayName("addNotes rejects a note written onto another user's activity")
    void addNotes_deniesNonOwner() {
        when(activityRepository.findById("act-1")).thenReturn(Optional.of(othersActivity));

        assertThatThrownBy(() -> activityService.addNotes("attacker-9", "act-1", "hacked"))
                .isInstanceOf(AccessDeniedException.class);

        verify(activityRepository, never()).save(any());
        assertThat(othersActivity.getNotes()).isNull();
    }

    @Test
    @DisplayName("rateActivity allows the owner")
    void rateActivity_allowsOwner() {
        when(activityRepository.findById("act-1")).thenReturn(Optional.of(othersActivity));
        when(activityRepository.save(any(Activity.class))).thenAnswer(i -> i.getArgument(0));

        activityService.rateActivity("owner-1", "act-1", 4);

        assertThat(othersActivity.getDifficultyRating()).isEqualTo(4);
        verify(activityRepository).save(othersActivity);
    }

    @Test
    @DisplayName("addNotes allows the owner")
    void addNotes_allowsOwner() {
        when(activityRepository.findById("act-1")).thenReturn(Optional.of(othersActivity));
        when(activityRepository.save(any(Activity.class))).thenAnswer(i -> i.getArgument(0));

        activityService.addNotes("owner-1", "act-1", "felt good");

        assertThat(othersActivity.getNotes()).isEqualTo("felt good");
    }

    // ── Edit and delete (PLAN P0-4) ──────────────────────────────────────────
    // Same id-only addressing as rate and notes, so the same ownership rule applies —
    // and here getting it wrong would let someone destroy another user's history.

    @Test
    @DisplayName("deleteActivity rejects a delete on another user's activity")
    void deleteActivity_deniesNonOwner() {
        when(activityRepository.findById("act-1")).thenReturn(Optional.of(othersActivity));

        assertThatThrownBy(() -> activityService.deleteActivity("attacker-9", "act-1"))
                .isInstanceOf(AccessDeniedException.class);

        verify(activityRepository, never()).delete(any());
        verify(recalculationService, never()).recalculateFor(any());
    }

    @Test
    @DisplayName("updateActivity rejects an edit on another user's activity")
    void updateActivity_deniesNonOwner() {
        when(activityRepository.findById("act-1")).thenReturn(Optional.of(othersActivity));

        ActivityRequest edit = new ActivityRequest();
        edit.setDuration(999);

        assertThatThrownBy(() -> activityService.updateActivity("attacker-9", "act-1", edit))
                .isInstanceOf(AccessDeniedException.class);

        verify(activityRepository, never()).save(any());
        assertThat(othersActivity.getDuration()).isEqualTo(30);
    }

    @Test
    @DisplayName("deleteActivity removes the row and rebuilds derived state")
    void deleteActivity_allowsOwnerAndRecalculates() {
        when(activityRepository.findById("act-1")).thenReturn(Optional.of(othersActivity));

        activityService.deleteActivity("owner-1", "act-1");

        // Pain logs are unlinked first, or the foreign key refuses the delete (PLAN P4-6).
        var order = inOrder(painLogRepository, activityRepository);
        order.verify(painLogRepository).unlinkActivity("act-1");
        order.verify(activityRepository).delete(othersActivity);
        // Without this, the deleted workout's streak day and personal best survive it.
        verify(recalculationService).recalculateFor(owner);
    }

    @Test
    @DisplayName("updateActivity applies changes and rebuilds derived state")
    void updateActivity_allowsOwnerAndRecalculates() {
        when(activityRepository.findById("act-1")).thenReturn(Optional.of(othersActivity));
        when(activityRepository.save(any(Activity.class))).thenAnswer(i -> i.getArgument(0));

        ActivityRequest edit = new ActivityRequest();
        edit.setDuration(45);
        edit.setNotes("corrected");

        activityService.updateActivity("owner-1", "act-1", edit);

        assertThat(othersActivity.getDuration()).isEqualTo(45);
        assertThat(othersActivity.getNotes()).isEqualTo("corrected");
        verify(recalculationService).recalculateFor(owner);
    }

    @Test
    @DisplayName("updateActivity leaves omitted fields alone")
    void updateActivity_isPartial() {
        othersActivity.setNotes("original note");
        when(activityRepository.findById("act-1")).thenReturn(Optional.of(othersActivity));
        when(activityRepository.save(any(Activity.class))).thenAnswer(i -> i.getArgument(0));

        ActivityRequest edit = new ActivityRequest();
        edit.setDuration(60);

        activityService.updateActivity("owner-1", "act-1", edit);

        assertThat(othersActivity.getDuration()).isEqualTo(60);
        assertThat(othersActivity.getNotes()).isEqualTo("original note");
        assertThat(othersActivity.getType()).isEqualTo(ActivityType.RUNNING);
    }
}
