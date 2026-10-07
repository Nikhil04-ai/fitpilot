package com.project.fitness.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The limits behind PLAN P4-17, at their edges. */
@DisplayName("InputChecks")
class InputChecksTest {

    @Test
    @DisplayName("limits are inclusive at both ends")
    void edgesAreAllowed() {
        assertThatCode(() -> {
            InputChecks.weightKg(20.0);   InputChecks.weightKg(500.0);
            InputChecks.heightCm(50.0);   InputChecks.heightCm(275.0);
            InputChecks.age(5);           InputChecks.age(120);
            InputChecks.painSeverity(1);  InputChecks.painSeverity(5);
            InputChecks.waterMl(1);       InputChecks.waterMl(5000);
            InputChecks.sleepHours(0.5);  InputChecks.sleepHours(24);
            InputChecks.sleepQuality(1);  InputChecks.sleepQuality(5);
            InputChecks.difficultyRating(1); InputChecks.difficultyRating(5);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("one step outside is refused")
    void justOutsideIsRefused() {
        assertThatThrownBy(() -> InputChecks.weightKg(19.9)).hasMessageContaining("between 20 and 500 kg");
        assertThatThrownBy(() -> InputChecks.weightKg(500.1)).isExactlyInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> InputChecks.heightCm(275.1)).hasMessageContaining("between 50 and 275 cm");
        assertThatThrownBy(() -> InputChecks.age(4)).hasMessageContaining("between 5 and 120 years");
        assertThatThrownBy(() -> InputChecks.painSeverity(6)).hasMessage("Pain severity must be between 1 and 5.");
        assertThatThrownBy(() -> InputChecks.waterMl(0)).hasMessageContaining("between 1 and 5000 ml");
        assertThatThrownBy(() -> InputChecks.sleepHours(24.01)).hasMessageContaining("at most 24");
        assertThatThrownBy(() -> InputChecks.sleepHours(0)).hasMessageContaining("more than 0");
        assertThatThrownBy(() -> InputChecks.sleepQuality(0)).hasMessageContaining("Sleep quality");
        assertThatThrownBy(() -> InputChecks.difficultyRating(6)).hasMessage("Rating must be between 1 and 5");
    }

    @Test
    @DisplayName("not-a-number is refused, since every comparison with it is false")
    void nanIsRefused() {
        assertThatThrownBy(() -> InputChecks.weightKg(Double.NaN)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> InputChecks.waterMl(Double.NaN)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> InputChecks.sleepHours(Double.NaN)).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("an omitted optional value is not a violation")
    void nullMeansNotChanging() {
        assertThatCode(() -> {
            InputChecks.weightKg(null);
            InputChecks.heightCm(null);
            InputChecks.age(null);
            InputChecks.difficultyRating(null);
        }).doesNotThrowAnyException();
    }
}
