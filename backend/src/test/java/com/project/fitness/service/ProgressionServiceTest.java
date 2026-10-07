package com.project.fitness.service;

import com.project.fitness.model.Activity;
import com.project.fitness.model.ActivityType;
import com.project.fitness.model.Exercise;
import com.project.fitness.model.User;
import com.project.fitness.repository.ActivityRepository;
import com.project.fitness.repository.ExerciseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Progression advice, now derived from load where load exists (PLAN P1-1) and only
 * from self-reported difficulty where it doesn't.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProgressionService Tests")
class ProgressionServiceTest {

    @Mock private ActivityRepository activityRepository;
    @Mock private ExerciseRepository exerciseRepository;
    @InjectMocks private ProgressionService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id("user-1").build();
    }

    /** One logged set, attached to a workout on the given day. */
    private Exercise setOn(int daysAgo, String name, int sets, int reps, Double weight) {
        Exercise e = new Exercise();
        e.setName(name);
        e.setSets(sets);
        e.setReps(reps);
        e.setWeightKg(weight);
        Activity a = Activity.builder()
                .id("act-" + daysAgo)
                .user(user).type(ActivityType.WEIGHT_TRAINING)
                .startTime(LocalDateTime.now().minusDays(daysAgo))
                .build();
        e.setActivity(a);
        return e;
    }

    private Activity liftingSession(Exercise... exercises) {
        Activity a = Activity.builder()
                .user(user).type(ActivityType.WEIGHT_TRAINING)
                .startTime(LocalDateTime.now()).build();
        a.replaceExercises(List.of(exercises));
        return a;
    }

    private Activity cardio(Integer rating) {
        return Activity.builder()
                .user(user).type(ActivityType.RUNNING)
                .difficultyRating(rating).startTime(LocalDateTime.now()).build();
    }

    // ── Load-driven ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("rising load across three sessions is reported as progress")
    void loadGoingUp() {
        when(exerciseRepository.findForUserByName("user-1", "bench press")).thenReturn(List.of(
                setOn(0, "Bench Press", 3, 8, 70.0),
                setOn(7, "Bench Press", 3, 8, 65.0),
                setOn(14, "Bench Press", 3, 8, 60.0)));

        String advice = service.analyseProgression("user-1",
                liftingSession(setOn(0, "Bench Press", 3, 8, 70.0)));

        assertThat(advice).contains("Bench Press is up").contains("Keep the current progression going");
        // The difficulty fallback must not have been consulted at all.
        verify(activityRepository, never()).findTop3ByUserIdAndTypeOrderByStartTimeDesc(any(), any());
    }

    @Test
    @DisplayName("flat load across three sessions is called out as stalled, with what to do")
    void loadFlat() {
        when(exerciseRepository.findForUserByName("user-1", "bench press")).thenReturn(List.of(
                setOn(0, "Bench Press", 3, 8, 60.0),
                setOn(7, "Bench Press", 3, 8, 60.0),
                setOn(14, "Bench Press", 3, 8, 60.0)));

        String advice = service.analyseProgression("user-1",
                liftingSession(setOn(0, "Bench Press", 3, 8, 60.0)));

        assertThat(advice).contains("Bench Press has not moved")
                .contains("Add a small amount of weight");
    }

    @Test
    @DisplayName("falling load is reported as a drop, not as progress")
    void loadGoingDown() {
        when(exerciseRepository.findForUserByName("user-1", "squat")).thenReturn(List.of(
                setOn(0, "Squat", 5, 5, 90.0),
                setOn(7, "Squat", 5, 5, 100.0),
                setOn(14, "Squat", 5, 5, 110.0)));

        String advice = service.analyseProgression("user-1",
                liftingSession(setOn(0, "Squat", 5, 5, 90.0)));

        assertThat(advice).contains("Squat has dropped");
    }

    @Test
    @DisplayName("several sets on one day count as one session, not several")
    void multipleSetsInOneSessionCollapse() {
        // Three sets today and three a week ago: two sessions, not six. Not enough
        // to read a trend, so this must fall through rather than invent one.
        List<Exercise> rows = new ArrayList<>();
        for (int i = 0; i < 3; i++) rows.add(setOn(0, "Bench Press", 1, 8, 60.0));
        for (int i = 0; i < 3; i++) rows.add(setOn(7, "Bench Press", 1, 8, 55.0));
        when(exerciseRepository.findForUserByName("user-1", "bench press")).thenReturn(rows);
        when(activityRepository.findTop3ByUserIdAndTypeOrderByStartTimeDesc(eq("user-1"), any()))
                .thenReturn(List.of());

        String advice = service.analyseProgression("user-1",
                liftingSession(setOn(0, "Bench Press", 3, 8, 60.0)));

        assertThat(advice).isEmpty();
    }

    @Test
    @DisplayName("a mix of moving and stalled lifts points at the stalled ones")
    void mixedLifts() {
        when(exerciseRepository.findForUserByName("user-1", "bench press")).thenReturn(List.of(
                setOn(0, "Bench Press", 3, 8, 70.0),
                setOn(7, "Bench Press", 3, 8, 65.0),
                setOn(14, "Bench Press", 3, 8, 60.0)));
        when(exerciseRepository.findForUserByName("user-1", "squat")).thenReturn(List.of(
                setOn(0, "Squat", 5, 5, 100.0),
                setOn(7, "Squat", 5, 5, 100.0),
                setOn(14, "Squat", 5, 5, 100.0)));

        String advice = service.analyseProgression("user-1", liftingSession(
                setOn(0, "Bench Press", 3, 8, 70.0),
                setOn(0, "Squat", 5, 5, 100.0)));

        assertThat(advice).contains("Bench Press is up")
                .contains("Squat has not moved")
                .contains("Push the stalled lifts specifically");
    }

    @Test
    @DisplayName("fewer than three sessions of a movement says nothing about it")
    void notEnoughHistory() {
        when(exerciseRepository.findForUserByName("user-1", "bench press")).thenReturn(List.of(
                setOn(0, "Bench Press", 3, 8, 60.0),
                setOn(7, "Bench Press", 3, 8, 55.0)));
        when(activityRepository.findTop3ByUserIdAndTypeOrderByStartTimeDesc(eq("user-1"), any()))
                .thenReturn(List.of());

        assertThat(service.analyseProgression("user-1",
                liftingSession(setOn(0, "Bench Press", 3, 8, 60.0)))).isEmpty();
    }

    @Test
    @DisplayName("bodyweight movements have no load to trend, so they are skipped")
    void bodyweightHasNoLoadTrend() {
        when(exerciseRepository.findForUserByName("user-1", "push ups")).thenReturn(List.of(
                setOn(0, "Push Ups", 3, 20, null),
                setOn(7, "Push Ups", 3, 18, null),
                setOn(14, "Push Ups", 3, 15, null)));
        when(activityRepository.findTop3ByUserIdAndTypeOrderByStartTimeDesc(eq("user-1"), any()))
                .thenReturn(List.of());

        assertThat(service.analyseProgression("user-1",
                liftingSession(setOn(0, "Push Ups", 3, 20, null)))).isEmpty();
    }

    // ── Difficulty fallback ──────────────────────────────────────────────────

    @Test
    @DisplayName("cardio still falls back to the difficulty heuristic")
    void cardioUsesDifficultyFallback() {
        when(activityRepository.findTop3ByUserIdAndTypeOrderByStartTimeDesc("user-1", ActivityType.RUNNING))
                .thenReturn(List.of(cardio(2), cardio(1), cardio(2)));

        String advice = service.analyseProgression("user-1", cardio(2));

        assertThat(advice).contains("all felt easy").contains("increasing intensity");
    }

    @Test
    @DisplayName("three hard cardio sessions still suggest backing off")
    void cardioHardFallback() {
        when(activityRepository.findTop3ByUserIdAndTypeOrderByStartTimeDesc("user-1", ActivityType.RUNNING))
                .thenReturn(List.of(cardio(5), cardio(4), cardio(4)));

        assertThat(service.analyseProgression("user-1", cardio(5)))
                .contains("all felt very hard").contains("rest day");
    }

    @Test
    @DisplayName("an unrated session in the window means no difficulty verdict")
    void unratedSessionBlocksFallback() {
        when(activityRepository.findTop3ByUserIdAndTypeOrderByStartTimeDesc("user-1", ActivityType.RUNNING))
                .thenReturn(List.of(cardio(2), cardio(null), cardio(2)));

        assertThat(service.analyseProgression("user-1", cardio(2))).isEmpty();
    }

    @Test
    @DisplayName("load wins over difficulty when both could speak")
    void loadTakesPrecedenceOverDifficulty() {
        when(exerciseRepository.findForUserByName("user-1", "deadlift")).thenReturn(List.of(
                setOn(0, "Deadlift", 3, 5, 140.0),
                setOn(7, "Deadlift", 3, 5, 130.0),
                setOn(14, "Deadlift", 3, 5, 120.0)));
        lenient().when(activityRepository.findTop3ByUserIdAndTypeOrderByStartTimeDesc(eq("user-1"), any()))
                .thenReturn(List.of(cardio(1), cardio(1), cardio(1)));

        String advice = service.analyseProgression("user-1",
                liftingSession(setOn(0, "Deadlift", 3, 5, 140.0)));

        // Measured load beats "it felt easy" — that is the whole point of P1-1.
        assertThat(advice).contains("Deadlift is up").doesNotContain("felt easy");
    }
}
