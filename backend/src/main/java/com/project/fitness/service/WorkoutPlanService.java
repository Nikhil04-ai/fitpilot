package com.project.fitness.service;

import com.project.fitness.exceptions.AccountNotFoundException;
import com.project.fitness.model.*;
import com.project.fitness.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WorkoutPlanService {

    private final WorkoutPlanRepository planRepository;
    private final UserPlanEnrollmentRepository enrollmentRepository;
    private final UserRepository userRepository;

    // ── Seed system plans on startup ────────────────────────────────────────

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedSystemPlans() {
        if (!planRepository.findByCreatedByUserIdIsNull().isEmpty()) return; // already seeded

        createSystemPlan("7-Day Beginner Cardio", "Perfect starting point. Light cardio every day with one rest day.", 7, "BEGINNER", "ENDURANCE",
                List.of(
                        day(1, ActivityType.WALKING, 30, "Brisk walk, comfortable pace", false),
                        day(2, ActivityType.CARDIO, 20, "Light cardio of your choice", false),
                        day(3, ActivityType.WALKING, 35, "Walk with a few 1-min jog intervals", false),
                        day(4, null, 0, "Rest and recovery", true),
                        day(5, ActivityType.CARDIO, 25, "Moderate cardio session", false),
                        day(6, ActivityType.WALKING, 40, "Longest walk of the week", false),
                        day(7, ActivityType.STRETCHING, 20, "Full body stretch and cool down", false)
                ));

        createSystemPlan("7-Day Strength Starter", "Introduction to weight training with adequate rest days.", 7, "BEGINNER", "MUSCLE_GAIN",
                List.of(
                        day(1, ActivityType.WEIGHT_TRAINING, 45, "Upper body: 3x10 push-ups, rows, shoulder press", false),
                        day(2, null, 0, "Rest", true),
                        day(3, ActivityType.WEIGHT_TRAINING, 45, "Lower body: 3x10 squats, lunges, glute bridges", false),
                        day(4, ActivityType.STRETCHING, 20, "Active recovery stretch", false),
                        day(5, ActivityType.WEIGHT_TRAINING, 45, "Full body compound movements", false),
                        day(6, ActivityType.WALKING, 30, "Light walk", false),
                        day(7, null, 0, "Rest", true)
                ));

        createSystemPlan("30-Day Fat Loss Challenge", "Progressively harder workouts to maximise calorie burn.", 30, "INTERMEDIATE", "WEIGHT_LOSS",
                buildProgressivePlan(30));

        createSystemPlan("14-Day Flexibility Program", "Daily yoga and stretching to improve mobility.", 14, "BEGINNER", "FLEXIBILITY",
                buildFlexibilityPlan(14));
    }

    // ── Enroll ──────────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> enrollUser(String userId, String planId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AccountNotFoundException(userId));
        WorkoutPlan plan = planRepository.findById(planId)
                .orElseThrow(() -> new RuntimeException("Plan not found: " + planId));

        // Deactivate any existing active enrollment
        enrollmentRepository.findByUserIdAndIsActiveTrue(userId)
                .ifPresent(e -> { e.setIsActive(false); enrollmentRepository.save(e); });

        UserPlanEnrollment enrollment = UserPlanEnrollment.builder()
                .user(user).plan(plan).startDate(LocalDate.now()).build();
        enrollmentRepository.save(enrollment);

        return buildEnrollmentResponse(enrollment);
    }

    // ── Mark today's workout complete ────────────────────────────────────────

    @Transactional
    public Map<String, Object> completeTodaysDay(String userId) {
        UserPlanEnrollment enrollment = enrollmentRepository.findByUserIdAndIsActiveTrue(userId)
                .orElseThrow(() -> new RuntimeException("No active plan. Enroll in a plan first."));

        // Guard against double-submits: pressing "mark complete" twice used to skip
        // a day of the plan each time, so a 7-day plan could be "finished" in one
        // sitting without the progress meaning anything.
        if (enrollment.getLastCompletedDate() != null
                && enrollment.getLastCompletedDate().isEqual(LocalDate.now())) {
            throw new RuntimeException("Today's workout is already marked complete. Come back tomorrow.");
        }
        enrollment.setLastCompletedDate(LocalDate.now());

        enrollment.setCompletedDays(enrollment.getCompletedDays() + 1);
        enrollment.setCurrentDay(enrollment.getCurrentDay() + 1);

        if (enrollment.getCurrentDay() > enrollment.getPlan().getTotalDays()) {
            enrollment.setIsActive(false);
            enrollment.setCompletedAt(LocalDate.now());
        }

        enrollmentRepository.save(enrollment);
        return buildEnrollmentResponse(enrollment);
    }

    // ── Read ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<WorkoutPlan> getAllSystemPlans() {
        return planRepository.findByCreatedByUserIdIsNull();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getActivePlan(String userId) {
        return enrollmentRepository.findByUserIdAndIsActiveTrue(userId)
                .map(this::buildEnrollmentResponse)
                .orElse(Map.of("message", "No active plan. Browse plans at GET /api/plans"));
    }

    @Transactional(readOnly = true)
    public WorkoutPlan getPlanDetail(String planId) {
        WorkoutPlan plan = planRepository.findById(planId)
                .orElseThrow(() -> new RuntimeException("Plan not found: " + planId));
        // The days are lazy and are serialised after this transaction has ended. That
        // only worked because production keeps the session open for the whole request
        // (open-in-view); anywhere else it was a 500 (AGENT.md rule 3). Loaded here.
        plan.getDays().size();
        return plan;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Map<String, Object> buildEnrollmentResponse(UserPlanEnrollment e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enrollmentId", e.getId());
        map.put("planName", e.getPlan().getName());
        map.put("planDifficulty", e.getPlan().getDifficulty());
        map.put("planGoal", e.getPlan().getGoal());
        map.put("startDate", e.getStartDate());
        map.put("currentDay", e.getCurrentDay());
        map.put("totalDays", e.getPlan().getTotalDays());
        map.put("completedDays", e.getCompletedDays());
        map.put("progressPercent",
                Math.round((e.getCompletedDays() * 100.0 / e.getPlan().getTotalDays()) * 10.0) / 10.0);
        map.put("isCompleted", !e.getIsActive());

        if (e.getIsActive() && e.getCurrentDay() <= e.getPlan().getTotalDays()) {
            WorkoutPlanDay today = e.getPlan().getDays().stream()
                    .filter(d -> d.getDayNumber().equals(e.getCurrentDay()))
                    .findFirst().orElse(null);
            if (today != null) {
                Map<String, Object> todayMap = new LinkedHashMap<>();
                todayMap.put("dayNumber", today.getDayNumber());
                todayMap.put("activityType", today.getActivityType());
                todayMap.put("durationMinutes", today.getDurationMinutes());
                todayMap.put("instructions", today.getInstructions());
                todayMap.put("isRestDay", today.getIsRestDay());
                map.put("todaysWorkout", todayMap);
            }
        }
        return map;
    }

    private void createSystemPlan(String name, String desc, int days, String diff, String goal,
                                   List<WorkoutPlanDay> dayList) {
        WorkoutPlan plan = WorkoutPlan.builder()
                .name(name).description(desc).totalDays(days)
                .difficulty(diff).goal(goal).build();
        WorkoutPlan saved = planRepository.save(plan);
        dayList.forEach(d -> d.setPlan(saved));
        saved.getDays().addAll(dayList);
        planRepository.save(saved);
    }

    private WorkoutPlanDay day(int num, ActivityType type, int mins, String instructions, boolean rest) {
        return WorkoutPlanDay.builder().dayNumber(num).activityType(type)
                .durationMinutes(mins).instructions(instructions).isRestDay(rest).build();
    }

    private List<WorkoutPlanDay> buildProgressivePlan(int totalDays) {
        List<WorkoutPlanDay> days = new ArrayList<>();
        for (int i = 1; i <= totalDays; i++) {
            boolean rest = (i % 7 == 0);
            int duration = 20 + (i / 7) * 5; // increase every week
            ActivityType type = (i % 3 == 0) ? ActivityType.HIIT : ActivityType.CARDIO;
            days.add(day(i, rest ? null : type, rest ? 0 : duration,
                    rest ? "Rest and recover" : "Cardio session, week " + ((i / 7) + 1) + " intensity", rest));
        }
        return days;
    }

    private List<WorkoutPlanDay> buildFlexibilityPlan(int totalDays) {
        List<WorkoutPlanDay> days = new ArrayList<>();
        String[] focuses = {"Hips and hamstrings", "Shoulders and upper back", "Full body flow",
                "Spine and core", "Hip flexors and quads", "Chest and arms", "Full body restoration"};
        for (int i = 1; i <= totalDays; i++) {
            days.add(day(i, ActivityType.YOGA, 20 + (i > 7 ? 10 : 0),
                    focuses[(i - 1) % 7] + " — hold each stretch 30–45 seconds", false));
        }
        return days;
    }
}
