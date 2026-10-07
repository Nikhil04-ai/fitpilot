package com.project.fitness.service;

import java.math.BigDecimal;

/**
 * The limits on numbers a person types in (PLAN P4-17).
 *
 * <p>Pain severity, water, sleep, the difficulty rating and the profile's weight,
 * height and age used to accept anything: a negative drink, a 40-hour night, a weight
 * of -5 kg. They fed averages, goals, BMI and the weekly digest. Each failure is a
 * plain {@code RuntimeException}, which {@code GlobalExceptionHandler} turns into a 400
 * whose {@code error} names the limit.
 *
 * <p>The bounds are deliberately wide. They exist to refuse values that cannot be true,
 * not to second-guess ones that are merely unusual.
 *
 * <p>Call these after the account lookup, so a dead session still gets its 409.
 */
public final class InputChecks {

    public static final double MIN_WEIGHT_KG = 20, MAX_WEIGHT_KG = 500;
    public static final double MIN_HEIGHT_CM = 50, MAX_HEIGHT_CM = 275;
    public static final int MIN_AGE = 5, MAX_AGE = 120;
    public static final double MAX_WATER_ML = 5000;
    public static final double MAX_SLEEP_HOURS = 24;

    private InputChecks() {}

    /** Weight in kg. {@code null} means "not being changed" and passes. */
    public static void weightKg(Double kg) {
        if (kg != null) range("Weight", kg, MIN_WEIGHT_KG, MAX_WEIGHT_KG, " kg");
    }

    /** Height in cm. {@code null} passes. */
    public static void heightCm(Double cm) {
        if (cm != null) range("Height", cm, MIN_HEIGHT_CM, MAX_HEIGHT_CM, " cm");
    }

    /** Age in years. {@code null} passes. */
    public static void age(Integer years) {
        if (years != null) range("Age", years, MIN_AGE, MAX_AGE, " years");
    }

    /** A pain rating, 1 (barely there) to 5 (severe). */
    public static void painSeverity(int severity) {
        range("Pain severity", severity, 1, 5, "");
    }

    /** One drink, in ml. An omitted amount arrives as 0 and is refused here too. */
    public static void waterMl(double ml) {
        range("Water amount", ml, 1, MAX_WATER_ML, " ml");
    }

    /** A night's sleep, in hours: more than none, at most a day. */
    public static void sleepHours(double hours) {
        if (!(hours > 0 && hours <= MAX_SLEEP_HOURS)) {
            throw new RuntimeException("Hours slept must be more than 0 and at most 24.");
        }
    }

    /** Sleep quality, 1 to 5. It used to be clamped silently, so a missing one became a 1. */
    public static void sleepQuality(int rating) {
        range("Sleep quality", rating, 1, 5, "");
    }

    /** The 1-5 effort rating on a workout. {@code null} means "not rated" and passes. */
    public static void difficultyRating(Integer rating) {
        if (rating != null && (rating < 1 || rating > 5)) {
            throw new RuntimeException("Rating must be between 1 and 5");
        }
    }

    private static void range(String what, double value, double min, double max, String unit) {
        // Written so NaN fails: every comparison with NaN is false.
        if (!(value >= min && value <= max)) {
            throw new RuntimeException(what + " must be between " + plain(min) + " and " + plain(max) + unit + ".");
        }
    }

    private static String plain(double n) {
        return BigDecimal.valueOf(n).stripTrailingZeros().toPlainString();
    }
}
