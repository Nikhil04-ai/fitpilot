package com.project.fitness.model;

/**
 * Which sitting a meal belongs to.
 *
 * <p>Grouping only — nothing calculates from it. It exists so the day reads back as a
 * day ("breakfast 420, lunch 680, …") rather than as an undifferentiated list, which
 * is how people check whether they have room left for dinner.
 */
public enum MealType {
    BREAKFAST, LUNCH, DINNER, SNACK, OTHER
}
