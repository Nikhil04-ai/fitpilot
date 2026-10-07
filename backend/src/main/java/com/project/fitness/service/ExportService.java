package com.project.fitness.service;

import com.project.fitness.exceptions.AccountNotFoundException;
import com.project.fitness.model.*;
import com.project.fitness.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Data export (PLAN P2-5).
 *
 * <p>The app holds a year of someone's training history and had no way to get it out.
 * For something that asks you to log every workout, every meal and every night's sleep,
 * that is a conspicuous absence — and the longer it was left, the more tables there
 * were to retrofit it across.
 *
 * <p>Everything here is keyed on the caller's own id. There is no dataset that can be
 * asked for on someone else's behalf, and no {@code userId} parameter to tamper with.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportService {

    private final UserRepository userRepository;
    private final ActivityRepository activityRepository;
    private final ExerciseRepository exerciseRepository;
    private final MealLogRepository mealLogRepository;
    private final GoalRepository goalRepository;
    private final BodyMetricsLogRepository bodyMetricsLogRepository;
    private final SleepLogRepository sleepLogRepository;
    private final WaterLogRepository waterLogRepository;
    private final PainLogRepository painLogRepository;
    private final PersonalBestRepository personalBestRepository;
    private final ExercisePersonalBestRepository exercisePersonalBestRepository;

    /**
     * Every dataset, with its column order.
     *
     * <p>Declared rather than derived from the first row's keys, so an empty dataset
     * still exports its header line — a CSV with no columns tells the reader nothing
     * about what they asked for, and a spreadsheet opens it as a single blank cell.
     */
    private static final Map<String, List<String>> HEADERS = new LinkedHashMap<>();
    static {
        HEADERS.put("activities", List.of("id", "type", "startTime", "completedAt", "durationMinutes",
                "caloriesBurned", "distanceKm", "totalVolumeKg", "difficultyRating", "notes", "createdAt"));
        HEADERS.put("exercises", List.of("id", "activityId", "activityStartTime", "name", "sets", "reps",
                "weightKg", "volumeKg", "estimatedOneRepMax", "orderIndex"));
        HEADERS.put("meals", List.of("id", "loggedAt", "mealType", "description", "calories",
                "proteinGrams", "carbsGrams", "fatGrams", "aiEstimated", "createdAt"));
        HEADERS.put("goals", List.of("id", "type", "period", "direction", "unit", "targetValue",
                "currentValue", "startValue", "periodStart", "deadline", "isCompleted", "createdAt"));
        HEADERS.put("body-metrics", List.of("loggedAt", "weightKg", "heightCm", "bmi", "bmiCategory"));
        HEADERS.put("sleep", List.of("id", "sleepDate", "hoursSlept", "qualityRating",
                "bedtime", "wakeTime", "notes", "loggedAt"));
        HEADERS.put("water", List.of("id", "logDate", "amountMl", "loggedAt"));
        HEADERS.put("pain", List.of("id", "loggedAt", "bodyPart", "severity", "description"));
        HEADERS.put("personal-bests", List.of("activityType", "bestDuration", "bestCalories",
                "bestDistanceKm", "achievedAt"));
        HEADERS.put("exercise-records", List.of("exercise", "bestWeightKg", "bestWeightReps",
                "bestEstimatedOneRepMax", "bestVolume", "bestBodyweightReps", "achievedAt"));
    }

    public static List<String> datasets() {
        return List.copyOf(HEADERS.keySet());
    }

    public static boolean isDataset(String name) {
        return HEADERS.containsKey(name);
    }

    /**
     * Throws {@link AccountNotFoundException} if the caller's account is gone.
     *
     * <p>The streamed routes must call this before writing a single header (PLAN P4-1).
     * Once the response is committed, the 409 the frontend self-heals from can no
     * longer be sent, and closing the stream ships an empty file with a 200 on it.
     */
    @Transactional(readOnly = true)
    public void requireAccount(String userId) {
        userRepository.findById(userId).orElseThrow(() -> new AccountNotFoundException(userId));
    }

    // ── JSON ─────────────────────────────────────────────────────────────────

    /**
     * The whole account as one document: profile, then every dataset.
     *
     * <p>Built from the same row-builders the CSVs use, so the two exports cannot drift
     * into disagreeing about what the data is.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> buildJsonExport(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AccountNotFoundException(userId));

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("exportedAt", LocalDateTime.now().toString());
        document.put("schemaVersion", 1);
        document.put("profile", profileOf(user));

        Map<String, Object> data = new LinkedHashMap<>();
        for (String dataset : HEADERS.keySet()) {
            data.put(dataset, rowsOf(userId, dataset));
        }
        document.put("data", data);
        return document;
    }

    private Map<String, Object> profileOf(User user) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", user.getId());
        profile.put("email", user.getEmail());
        profile.put("firstName", user.getFirstName());
        profile.put("lastName", user.getLastName());
        profile.put("weightKg", user.getWeight());
        profile.put("heightCm", user.getHeight());
        profile.put("age", user.getAge());
        profile.put("currentStreak", user.getCurrentStreak());
        profile.put("longestStreak", user.getLongestStreak());
        profile.put("memberSince", user.getCreatedAt());
        // password and role are deliberately absent. An export is a file that gets
        // emailed to people and dropped in cloud storage; the hash has no business
        // travelling with it, and role is not the user's to know or to carry.
        return profile;
    }

    // ── CSV ──────────────────────────────────────────────────────────────────

    /** Writes one dataset as CSV. The caller owns the writer and closes it. */
    @Transactional(readOnly = true)
    public void writeCsv(String userId, String dataset, Writer out) throws IOException {
        List<String> headers = HEADERS.get(dataset);
        if (headers == null) {
            throw new RuntimeException("Unknown dataset: " + dataset
                    + ". Available: " + String.join(", ", datasets()));
        }
        requireAccount(userId);

        out.write(String.join(",", headers));
        out.write("\r\n");

        for (Map<String, Object> row : rowsOf(userId, dataset)) {
            List<String> cells = new ArrayList<>(headers.size());
            for (String header : headers) cells.add(csvCell(row.get(header)));
            out.write(String.join(",", cells));
            out.write("\r\n");
        }
        out.flush();
    }

    /** Writes every dataset as a CSV inside one zip. */
    @Transactional(readOnly = true)
    public void writeZip(String userId, OutputStream out) throws IOException {
        requireAccount(userId);

        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (String dataset : HEADERS.keySet()) {
                zip.putNextEntry(new ZipEntry("fitpilot-" + dataset + ".csv"));
                // Not closed: closing the writer would close the whole zip stream after
                // the first entry. Flushed instead, which is what pushes the entry out.
                Writer writer = new OutputStreamWriter(zip, StandardCharsets.UTF_8);
                writeCsv(userId, dataset, writer);
                writer.flush();
                zip.closeEntry();
            }
        }
    }

    /**
     * One CSV cell, quoted and escaped.
     *
     * <p><b>Formula injection.</b> A cell beginning {@code = + - @}, tab or carriage
     * return is executed as a formula when the file is opened in Excel, Sheets or
     * LibreOffice — so a workout note reading {@code =HYPERLINK("http://evil","click")}
     * becomes a live link in the exported spreadsheet, and other payloads can reach the
     * shell. Notes, meal descriptions and pain descriptions are all free text that
     * lands in this file, so every such cell is prefixed with an apostrophe, which
     * spreadsheets read as "treat what follows as text" and do not display.
     *
     * <p>The value is still quoted afterwards, so a comma or a newline inside it cannot
     * break the row apart either.
     */
    static String csvCell(Object value) {
        if (value == null) return "";

        String text = String.valueOf(value);
        if (text.isEmpty()) return "";

        // A number can't carry a formula, and prefixing a negative one ('-0.6) turned
        // it into text in every spreadsheet (PLAN P4-9). Free text stays guarded even
        // when it looks numeric: a note reading "-1+1" would still evaluate.
        char first = text.charAt(0);
        if (!(value instanceof Number)
                && (first == '=' || first == '+' || first == '-' || first == '@'
                    || first == '\t' || first == '\r')) {
            text = "'" + text;
        }

        // RFC 4180: double the quotes, wrap the lot.
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    // ── Rows ─────────────────────────────────────────────────────────────────

    /**
     * One dataset as a list of ordered maps, keyed to match {@link #HEADERS}.
     *
     * <p>The single source of truth for both export formats. {@code ExportServiceTest}
     * asserts that no builder here emits a key the headers don't declare, which is the
     * failure mode this shape invites: a field added to a row and not to the header
     * list would silently vanish from every CSV while still appearing in the JSON.
     */
    List<Map<String, Object>> rowsOf(String userId, String dataset) {
        return switch (dataset) {
            case "activities" -> activityRows(userId);
            case "exercises" -> exerciseRows(userId);
            case "meals" -> mealRows(userId);
            case "goals" -> goalRows(userId);
            case "body-metrics" -> bodyMetricRows(userId);
            case "sleep" -> sleepRows(userId);
            case "water" -> waterRows(userId);
            case "pain" -> painRows(userId);
            case "personal-bests" -> personalBestRows(userId);
            case "exercise-records" -> exerciseRecordRows(userId);
            default -> List.of();
        };
    }

    private List<Map<String, Object>> activityRows(String userId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Activity a : activityRepository.findByUserId(userId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", a.getId());
            row.put("type", a.getType());
            row.put("startTime", a.getStartTime());
            row.put("completedAt", a.getCompletedAt());
            row.put("durationMinutes", a.getDuration());
            row.put("caloriesBurned", a.getCaloriesBurned());
            row.put("distanceKm", a.getDistanceKm());
            row.put("totalVolumeKg", a.totalVolume());
            row.put("difficultyRating", a.getDifficultyRating());
            row.put("notes", a.getNotes());
            row.put("createdAt", a.getCreatedAt());
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> exerciseRows(String userId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Exercise e : exerciseRepository.findAllForUserWithActivity(userId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", e.getId());
            row.put("activityId", e.getActivity() != null ? e.getActivity().getId() : null);
            // Denormalised on purpose: a movement without its date is unusable on its
            // own in a spreadsheet, and joining two CSVs by hand is not something
            // anyone exporting their training log wants to do.
            row.put("activityStartTime", e.getActivity() != null ? e.getActivity().getStartTime() : null);
            row.put("name", e.getName());
            row.put("sets", e.getSets());
            row.put("reps", e.getReps());
            row.put("weightKg", e.getWeightKg());
            row.put("volumeKg", e.volume());
            row.put("estimatedOneRepMax", e.estimatedOneRepMax());
            row.put("orderIndex", e.getOrderIndex());
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> mealRows(String userId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MealLog m : mealLogRepository.findByUserIdAndLoggedAtBetweenOrderByLoggedAtAsc(
                userId, LocalDateTime.of(1970, 1, 1, 0, 0), LocalDateTime.now().plusYears(1))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", m.getId());
            row.put("loggedAt", m.getLoggedAt());
            row.put("mealType", m.getMealType());
            row.put("description", m.getDescription());
            row.put("calories", m.getCalories());
            row.put("proteinGrams", m.getProteinGrams());
            row.put("carbsGrams", m.getCarbsGrams());
            row.put("fatGrams", m.getFatGrams());
            // Exported so the estimate stays labelled outside the app too — a guessed
            // calorie count that loses its badge on the way out becomes indistinguishable
            // from a measured one for whoever reads the file.
            row.put("aiEstimated", m.getAiEstimated());
            row.put("createdAt", m.getCreatedAt());
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> goalRows(String userId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Goal g : goalRepository.findByUserId(userId)) {
            GoalType type = g.getType();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", g.getId());
            row.put("type", type);
            row.put("period", type != null ? type.period() : null);
            row.put("direction", type != null ? type.direction() : null);
            row.put("unit", type != null ? type.unit() : null);
            row.put("targetValue", g.getTargetValue());
            row.put("currentValue", g.getCurrentValue());
            row.put("startValue", g.getStartValue());
            row.put("periodStart", g.getPeriodStart());
            row.put("deadline", g.getDeadline());
            row.put("isCompleted", g.getIsCompleted());
            row.put("createdAt", g.getCreatedAt());
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> bodyMetricRows(String userId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BodyMetricsLog b : bodyMetricsLogRepository.findByUserIdOrderByLoggedAtAsc(userId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("loggedAt", b.getLoggedAt());
            row.put("weightKg", b.getWeight());
            row.put("heightCm", b.getHeight());
            row.put("bmi", b.getBmi());
            row.put("bmiCategory", b.getBmiCategory());
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> sleepRows(String userId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SleepLog s : sleepLogRepository.findByUserIdOrderBySleepDateDesc(userId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", s.getId());
            row.put("sleepDate", s.getSleepDate());
            row.put("hoursSlept", s.getHoursSlept());
            row.put("qualityRating", s.getQualityRating());
            row.put("bedtime", s.getBedtime());
            row.put("wakeTime", s.getWakeTime());
            row.put("notes", s.getNotes());
            row.put("loggedAt", s.getLoggedAt());
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> waterRows(String userId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WaterLog w : waterLogRepository.findByUserIdOrderByLogDateAsc(userId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", w.getId());
            row.put("logDate", w.getLogDate());
            row.put("amountMl", w.getAmountMl());
            row.put("loggedAt", w.getLoggedAt());
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> painRows(String userId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PainLog p : painLogRepository.findByUserId(userId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.getId());
            row.put("loggedAt", p.getLoggedAt());
            row.put("bodyPart", p.getBodyPart());
            row.put("severity", p.getSeverity());
            row.put("description", p.getDescription());
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> personalBestRows(String userId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PersonalBest pb : personalBestRepository.findByUserId(userId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("activityType", pb.getActivityType());
            row.put("bestDuration", pb.getBestDuration());
            row.put("bestCalories", pb.getBestCalories());
            row.put("bestDistanceKm", pb.getBestDistanceKm());
            row.put("achievedAt", pb.getAchievedAt());
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> exerciseRecordRows(String userId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ExercisePersonalBest r : exercisePersonalBestRepository.findByUserId(userId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("exercise", r.getDisplayName());
            row.put("bestWeightKg", r.getBestWeightKg());
            row.put("bestWeightReps", r.getBestWeightReps());
            row.put("bestEstimatedOneRepMax", r.getBestEstimatedOneRepMax());
            row.put("bestVolume", r.getBestVolume());
            row.put("bestBodyweightReps", r.getBestBodyweightReps());
            row.put("achievedAt", r.getAchievedAt());
            rows.add(row);
        }
        return rows;
    }
}
