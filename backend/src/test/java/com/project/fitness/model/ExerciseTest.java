package com.project.fitness.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two derived numbers every strength record is judged on (PLAN P1-1).
 */
@DisplayName("Exercise Tests")
class ExerciseTest {

    private Exercise set(Integer sets, Integer reps, Double weight) {
        Exercise e = new Exercise();
        e.setName("Bench Press");
        e.setSets(sets);
        e.setReps(reps);
        e.setWeightKg(weight);
        return e;
    }

    // ── Name normalisation ───────────────────────────────────────────────────

    @Test
    @DisplayName("case and spacing don't make two different movements")
    void normalisesName() {
        assertThat(Exercise.normalise("  Bench   PRESS ")).isEqualTo("bench press");
        assertThat(Exercise.normalise("bench press")).isEqualTo("bench press");
    }

    @Test
    @DisplayName("a blank name normalises to null rather than an empty key")
    void blankNameIsNull() {
        assertThat(Exercise.normalise("   ")).isNull();
        assertThat(Exercise.normalise(null)).isNull();
    }

    @Test
    @DisplayName("setting the name keeps the normalised form in step")
    void setterSyncsNormalisedName() {
        Exercise e = new Exercise();
        e.setName("Overhead  Press");
        assertThat(e.getNormalisedName()).isEqualTo("overhead press");
    }

    // ── Volume ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("volume is sets x reps x weight")
    void volume() {
        assertThat(set(3, 8, 60.0).volume()).isEqualTo(1440.0);
    }

    @Test
    @DisplayName("a bodyweight set has no volume rather than a volume of zero")
    void volumeNullWithoutLoad() {
        // Zero would look like a real, very bad session and could drag a volume
        // record down; null means "not measured in kg".
        assertThat(set(3, 10, null).volume()).isNull();
        assertThat(set(null, 10, 60.0).volume()).isNull();
        assertThat(set(3, null, 60.0).volume()).isNull();
    }

    // ── Estimated 1RM ────────────────────────────────────────────────────────

    @Test
    @DisplayName("a single is its own one-rep max")
    void oneRepMaxOfASingle() {
        assertThat(set(1, 1, 100.0).estimatedOneRepMax()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("Epley: 60kg x 8 estimates 76kg")
    void oneRepMaxEpley() {
        // 60 * (1 + 8/30) = 76.0
        assertThat(set(3, 8, 60.0).estimatedOneRepMax()).isEqualTo(76.0);
    }

    @Test
    @DisplayName("ranks a heavy triple above a light set of ten")
    void oneRepMaxIsComparableAcrossRepRanges() {
        Double heavyTriple = set(5, 3, 100.0).estimatedOneRepMax();   // 110.0
        Double lightTen    = set(3, 10, 70.0).estimatedOneRepMax();   //  93.3
        assertThat(heavyTriple).isGreaterThan(lightTen);
    }

    @Test
    @DisplayName("declines to estimate above 12 reps, where Epley stops holding")
    void oneRepMaxNullForHighReps() {
        assertThat(set(2, 20, 40.0).estimatedOneRepMax()).isNull();
        assertThat(set(2, 12, 40.0).estimatedOneRepMax()).isNotNull();
    }

    @Test
    @DisplayName("no weight means no strength estimate")
    void oneRepMaxNullWithoutWeight() {
        assertThat(set(3, 10, null).estimatedOneRepMax()).isNull();
        assertThat(set(3, 10, 0.0).estimatedOneRepMax()).isNull();
    }

    // ── Activity aggregation ─────────────────────────────────────────────────

    @Test
    @DisplayName("an activity totals the volume of every loaded movement")
    void activityTotalsVolume() {
        Activity activity = Activity.builder().type(ActivityType.WEIGHT_TRAINING).build();
        activity.replaceExercises(java.util.List.of(
                set(3, 8, 60.0),      // 1440
                set(5, 5, 100.0)));   // 2500

        assertThat(activity.totalVolume()).isEqualTo(3940.0);
    }

    @Test
    @DisplayName("replacing the exercise list renumbers them into entry order")
    void replaceExercisesSetsOrder() {
        Activity activity = Activity.builder().type(ActivityType.WEIGHT_TRAINING).build();
        activity.replaceExercises(java.util.List.of(set(3, 8, 60.0), set(5, 5, 100.0)));

        assertThat(activity.getExercises()).extracting(Exercise::getOrderIndex)
                .containsExactly(0, 1);
        assertThat(activity.getExercises()).allSatisfy(e ->
                assertThat(e.getActivity()).isSameAs(activity));
    }

    @Test
    @DisplayName("a cardio activity with no movements has no total volume")
    void cardioHasNoVolume() {
        Activity activity = Activity.builder().type(ActivityType.RUNNING).build();
        assertThat(activity.totalVolume()).isNull();
    }
}
