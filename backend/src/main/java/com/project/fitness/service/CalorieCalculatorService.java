package com.project.fitness.service;

import com.project.fitness.model.ActivityType;
import com.project.fitness.model.User;
import org.springframework.stereotype.Service;

/**
 * Feature 1 — Smart Calorie Auto-Calculator
 * Formula: Calories = MET × weight(kg) × duration(hours)
 *
 * <p>Also holds the resting side of the same equation (PLAN P2-1). Nutrition needs it:
 * "eaten minus what you burned exercising" is not a deficit and never was — it ignores
 * the ~1500 kcal a body spends existing, so every honest day of eating looked like a
 * large surplus. Without a maintenance figure to compare against, an intake log is a
 * list of numbers that cannot answer the only question anyone asks it.
 */
@Service
public class CalorieCalculatorService {

    /**
     * Sedentary multiplier applied to BMR to get maintenance.
     *
     * <p>Deliberately the lowest one in the standard table. Logged workouts are added on
     * top from the activities themselves, so anything higher would count training twice
     * — once in the multiplier and once in the activity burn — and quietly inflate every
     * calorie allowance in the app.
     */
    private static final double SEDENTARY_MULTIPLIER = 1.2;

    public int calculate(ActivityType type, int durationMinutes, double weightKg) {
        double met = getMet(type);
        double durationHours = durationMinutes / 60.0;
        return (int) Math.round(met * weightKg * durationHours);
    }

    /**
     * Basal metabolic rate by Mifflin-St Jeor, in kcal/day. Null when the profile is
     * missing weight, height or age — there is no sensible default for any of them, and
     * inventing one would put a fabricated number at the base of every nutrition figure.
     *
     * <p><b>Sex-neutral, on purpose.</b> Mifflin-St Jeor's constant is +5 for men and
     * -161 for women; {@link User} records no sex, so this uses the midpoint (-78). That
     * is an estimate with roughly ±80 kcal of built-in error, which is why every response
     * carrying it is labelled an estimate rather than presented as a measurement. Adding
     * an optional sex field to the profile is the fix if this needs to be tighter.
     */
    public Integer basalMetabolicRate(User user) {
        if (user == null) return null;
        Double weight = user.getWeight();
        Double height = user.getHeight();
        Integer age = user.getAge();
        if (weight == null || height == null || age == null) return null;
        if (weight <= 0 || height <= 0 || age <= 0) return null;

        double bmr = (10 * weight) + (6.25 * height) - (5.0 * age) - 78;
        return bmr > 0 ? (int) Math.round(bmr) : null;
    }

    /**
     * Calories burned in a day doing nothing in particular — BMR plus the sedentary
     * multiplier. Exercise is added separately from what was actually logged.
     */
    public Integer maintenanceCalories(User user) {
        Integer bmr = basalMetabolicRate(user);
        return bmr == null ? null : (int) Math.round(bmr * SEDENTARY_MULTIPLIER);
    }

    private double getMet(ActivityType type) {
        return switch (type) {
            case RUNNING        -> 9.8;
            case WALKING        -> 3.5;
            case CYCLING        -> 7.5;
            case SWIMMING       -> 8.0;
            case WEIGHT_TRAINING-> 6.0;
            case YOGA           -> 3.0;
            case HIIT           -> 10.0;
            case CARDIO         -> 7.0;
            case STRETCHING     -> 2.5;
            case OTHER          -> 5.0;
        };
    }
}
