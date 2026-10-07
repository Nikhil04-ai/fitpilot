package com.project.fitness.service;

import com.project.fitness.model.ActivityType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for CalorieCalculatorService — Feature 1
 * Formula: Calories = MET × weight(kg) × duration(hours)
 */
@DisplayName("CalorieCalculatorService Tests")
class CalorieCalculatorServiceTest {

    private CalorieCalculatorService calculatorService;

    @BeforeEach
    void setUp() {
        calculatorService = new CalorieCalculatorService();
    }

    // ── Running ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Running 30 min at 70kg: MET 9.8 → 343 cal")
    void running_30min_70kg_correctCalories() {
        // 9.8 × 70 × (30/60) = 9.8 × 70 × 0.5 = 343
        int result = calculatorService.calculate(ActivityType.RUNNING, 30, 70.0);
        assertThat(result).isEqualTo(343);
    }

    @Test
    @DisplayName("Running 60 min at 80kg: MET 9.8 → 784 cal")
    void running_60min_80kg_correctCalories() {
        // 9.8 × 80 × 1.0 = 784
        int result = calculatorService.calculate(ActivityType.RUNNING, 60, 80.0);
        assertThat(result).isEqualTo(784);
    }

    // ── HIIT has highest MET ──────────────────────────────────────────────────

    @Test
    @DisplayName("HIIT burns more than walking for same duration and weight")
    void hiit_burnMoreThanWalking() {
        int hiit    = calculatorService.calculate(ActivityType.HIIT, 30, 70.0);
        int walking = calculatorService.calculate(ActivityType.WALKING, 30, 70.0);
        assertThat(hiit).isGreaterThan(walking);
    }

    // ── Stretching has lowest MET ─────────────────────────────────────────────

    @Test
    @DisplayName("Stretching burns the least calories per minute")
    void stretching_lowestCalories() {
        int stretching = calculatorService.calculate(ActivityType.STRETCHING, 30, 70.0);
        int running    = calculatorService.calculate(ActivityType.RUNNING, 30, 70.0);
        assertThat(stretching).isLessThan(running);
    }

    // ── Heavier user burns more ───────────────────────────────────────────────

    @Test
    @DisplayName("Heavier user burns more calories for the same activity")
    void heavierUser_moreCalories() {
        int light = calculatorService.calculate(ActivityType.CYCLING, 30, 60.0);
        int heavy = calculatorService.calculate(ActivityType.CYCLING, 30, 100.0);
        assertThat(heavy).isGreaterThan(light);
    }

    // ── Longer duration burns more ────────────────────────────────────────────

    @Test
    @DisplayName("Longer duration burns proportionally more calories")
    void longerDuration_moreCalories() {
        int thirtyMin = calculatorService.calculate(ActivityType.SWIMMING, 30, 70.0);
        int sixtyMin  = calculatorService.calculate(ActivityType.SWIMMING, 60, 70.0);
        assertThat(sixtyMin).isEqualTo(thirtyMin * 2);
    }

    // ── Parameterised: all activity types return positive values ──────────────

    @ParameterizedTest(name = "{0} at 70kg for 30min should return positive calories")
    @CsvSource({
            "RUNNING, 30, 70.0",
            "WALKING, 30, 70.0",
            "CYCLING, 30, 70.0",
            "SWIMMING, 30, 70.0",
            "WEIGHT_TRAINING, 30, 70.0",
            "YOGA, 30, 70.0",
            "HIIT, 30, 70.0",
            "CARDIO, 30, 70.0",
            "STRETCHING, 30, 70.0",
            "OTHER, 30, 70.0"
    })
    @DisplayName("All activity types produce positive calorie values")
    void allActivityTypes_returnPositiveCalories(ActivityType type, int duration, double weight) {
        int result = calculatorService.calculate(type, duration, weight);
        assertThat(result).isPositive();
    }

    // ── Edge: very short duration ─────────────────────────────────────────────

    @Test
    @DisplayName("1 minute activity still returns a positive number")
    void oneMinute_stillPositive() {
        int result = calculatorService.calculate(ActivityType.RUNNING, 1, 70.0);
        assertThat(result).isPositive();
    }
}
