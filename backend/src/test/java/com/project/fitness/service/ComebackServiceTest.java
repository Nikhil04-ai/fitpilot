package com.project.fitness.service;

import com.project.fitness.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ComebackService — Feature 8
 */
@DisplayName("ComebackService Tests")
class ComebackServiceTest {

    private ComebackService comebackService;
    private User user;

    @BeforeEach
    void setUp() {
        comebackService = new ComebackService();
        user = User.builder().id("user-1").firstName("Nikhil").build();
    }

    // ── Not a comeback scenario ───────────────────────────────────────────────

    @Test
    @DisplayName("No previous activity: returns empty map (not a comeback)")
    void noLastActivity_notComeback() {
        user.setLastActivityDate(null);
        Map<String, Object> result = comebackService.evaluateComeback(user);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Logged yesterday: not a comeback (gap < 14 days)")
    void recentActivity_notComeback() {
        user.setLastActivityDate(LocalDate.now().minusDays(5));
        Map<String, Object> result = comebackService.evaluateComeback(user);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Exactly 13 days gap: NOT a comeback")
    void thirteenDays_notComeback() {
        user.setLastActivityDate(LocalDate.now().minusDays(13));
        Map<String, Object> result = comebackService.evaluateComeback(user);
        assertThat(result).isEmpty();
    }

    // ── Comeback triggered ────────────────────────────────────────────────────

    @Test
    @DisplayName("Exactly 14 days gap: comeback triggered at 40% intensity")
    void fourteenDays_comebackTriggered_40pct() {
        user.setLastActivityDate(LocalDate.now().minusDays(14));
        Map<String, Object> result = comebackService.evaluateComeback(user);

        assertThat(result).isNotEmpty();
        assertThat(result.get("comebackDetected")).isEqualTo(true);
        assertThat(result.get("recommendedIntensity")).isEqualTo("40%");
        assertThat(result.get("daysAway")).isEqualTo(14L);
        assertThat(result.get("message").toString()).contains("40%");
    }

    @Test
    @DisplayName("21 days gap: still Week 1 (boundary is <= 21), so 40% intensity")
    void twentyOneDays_stillWeek1_40pct() {
        // ComebackService: daysAway <= 21 → Week 1 (40%)
        // 21 is the LAST day of Week 1, not the first day of Week 2
        user.setLastActivityDate(LocalDate.now().minusDays(21));
        Map<String, Object> result = comebackService.evaluateComeback(user);

        assertThat(result.get("recommendedIntensity")).isEqualTo("40%");
        assertThat(result.get("weekLabel")).isEqualTo("Week 1");
    }

    @Test
    @DisplayName("22 days gap: first day of Week 2, 60% intensity")
    void twentyTwoDays_week2_60pct() {
        // 22 days > 21 → falls into daysAway <= 28 → Week 2 (60%)
        user.setLastActivityDate(LocalDate.now().minusDays(22));
        Map<String, Object> result = comebackService.evaluateComeback(user);

        assertThat(result.get("recommendedIntensity")).isEqualTo("60%");
        assertThat(result.get("weekLabel")).isEqualTo("Week 2");
    }

    @Test
    @DisplayName("30 days gap: week 3+ comeback at 80% intensity")
    void thirtyDays_week3_80pct() {
        user.setLastActivityDate(LocalDate.now().minusDays(30));
        Map<String, Object> result = comebackService.evaluateComeback(user);

        assertThat(result.get("recommendedIntensity")).isEqualTo("80%");
        assertThat(result.get("weekLabel")).isEqualTo("Week 3+");
    }

    // ── Response structure ────────────────────────────────────────────────────

    @Test
    @DisplayName("Comeback response has all required keys")
    void comebackResponse_hasAllRequiredKeys() {
        user.setLastActivityDate(LocalDate.now().minusDays(18));
        Map<String, Object> result = comebackService.evaluateComeback(user);

        assertThat(result).containsKeys(
                "comebackDetected", "daysAway",
                "recommendedIntensity", "weekLabel", "message");
    }
}
