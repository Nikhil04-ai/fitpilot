package com.project.fitness.service;

import com.project.fitness.exceptions.AccountNotFoundException;
import com.project.fitness.model.Activity;
import com.project.fitness.model.ActivityType;
import com.project.fitness.model.User;
import com.project.fitness.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ExportService — PLAN P2-5.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExportService Tests")
class ExportServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private ActivityRepository activityRepository;
    @Mock private ExerciseRepository exerciseRepository;
    @Mock private MealLogRepository mealLogRepository;
    @Mock private GoalRepository goalRepository;
    @Mock private BodyMetricsLogRepository bodyMetricsLogRepository;
    @Mock private SleepLogRepository sleepLogRepository;
    @Mock private WaterLogRepository waterLogRepository;
    @Mock private PainLogRepository painLogRepository;
    @Mock private PersonalBestRepository personalBestRepository;
    @Mock private ExercisePersonalBestRepository exercisePersonalBestRepository;
    @InjectMocks private ExportService exportService;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .id("user-1").email("runner@test.com").firstName("Nikhil")
                .password("$2a$10$averyrealbcryptwouldbehere")
                .weight(70.0).height(175.0).age(30)
                .build();
    }

    /** Every repository returns nothing, so a test can populate only what it cares about. */
    private void emptyEverything() {
        lenient().when(activityRepository.findByUserId(anyString())).thenReturn(List.of());
        lenient().when(exerciseRepository.findAllForUserWithActivity(anyString())).thenReturn(List.of());
        lenient().when(mealLogRepository.findByUserIdAndLoggedAtBetweenOrderByLoggedAtAsc(
                anyString(), any(), any())).thenReturn(List.of());
        lenient().when(goalRepository.findByUserId(anyString())).thenReturn(List.of());
        lenient().when(bodyMetricsLogRepository.findByUserIdOrderByLoggedAtAsc(anyString())).thenReturn(List.of());
        lenient().when(sleepLogRepository.findByUserIdOrderBySleepDateDesc(anyString())).thenReturn(List.of());
        lenient().when(waterLogRepository.findByUserIdOrderByLogDateAsc(anyString())).thenReturn(List.of());
        lenient().when(painLogRepository.findByUserId(anyString())).thenReturn(List.of());
        lenient().when(personalBestRepository.findByUserId(anyString())).thenReturn(List.of());
        lenient().when(exercisePersonalBestRepository.findByUserId(anyString())).thenReturn(List.of());
    }

    private static org.mockito.ArgumentMatcher<LocalDateTime> anyTime() { return t -> true; }
    private static LocalDateTime any() { return org.mockito.ArgumentMatchers.any(); }

    // ── CSV formula injection ────────────────────────────────────────────────

    @Nested
    @DisplayName("CSV cells")
    class CsvCells {

        /**
         * The reason this method exists at all.
         *
         * <p>A cell beginning = + - @ is executed as a formula when the file is opened
         * in Excel, Sheets or LibreOffice. Workout notes, meal descriptions and pain
         * descriptions are all free text that lands in this file, so a note reading
         * {@code =HYPERLINK("http://evil","Payslip")} would become a live link in a
         * spreadsheet the user believes is just their own data.
         */
        @Test
        @DisplayName("neutralise a leading formula character")
        void neutralisesFormulaCharacters() {
            assertThat(ExportService.csvCell("=1+1")).isEqualTo("\"'=1+1\"");
            assertThat(ExportService.csvCell("+SUM(A1)")).isEqualTo("\"'+SUM(A1)\"");
            assertThat(ExportService.csvCell("-2+3")).isEqualTo("\"'-2+3\"");
            assertThat(ExportService.csvCell("@SUM(A1)")).isEqualTo("\"'@SUM(A1)\"");
            assertThat(ExportService.csvCell("\tcmd")).isEqualTo("\"'\tcmd\"");
        }

        @Test
        @DisplayName("leave ordinary text alone apart from quoting it")
        void ordinaryTextIsJustQuoted() {
            assertThat(ExportService.csvCell("Felt great today")).isEqualTo("\"Felt great today\"");
            assertThat(ExportService.csvCell(42)).isEqualTo("\"42\"");
        }

        @Test
        @DisplayName("escape embedded quotes, commas and newlines rather than breaking the row")
        void escapesStructuralCharacters() {
            assertThat(ExportService.csvCell("He said \"hi\"")).isEqualTo("\"He said \"\"hi\"\"\"");
            assertThat(ExportService.csvCell("squats, then bench")).isEqualTo("\"squats, then bench\"");
            assertThat(ExportService.csvCell("line one\nline two")).isEqualTo("\"line one\nline two\"");
        }

        @Test
        @DisplayName("write a missing value as an empty cell, not the text \"null\"")
        void nullIsEmpty() {
            assertThat(ExportService.csvCell(null)).isEmpty();
            assertThat(ExportService.csvCell("")).isEmpty();
        }
    }

    // ── CSV output ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("CSV output")
    class CsvOutput {

        @Test
        @DisplayName("writes the header row even when there is nothing to export")
        void emptyDatasetStillHasHeaders() throws Exception {
            when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
            emptyEverything();

            StringWriter out = new StringWriter();
            exportService.writeCsv("user-1", "activities", out);

            // A CSV with no columns tells the reader nothing about what they asked for,
            // and a spreadsheet opens it as one blank cell.
            assertThat(out.toString()).startsWith("id,type,startTime");
            assertThat(out.toString().lines()).hasSize(1);
        }

        @Test
        @DisplayName("writes one row per record, in the declared column order")
        void writesRows() throws Exception {
            when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
            emptyEverything();

            Activity a = new Activity();
            a.setId("act-1");
            a.setType(ActivityType.RUNNING);
            a.setStartTime(LocalDateTime.of(2026, 9, 20, 7, 30));
            a.setDuration(30);
            a.setCaloriesBurned(320);
            a.setDistanceKm(5.0);
            a.setNotes("Felt great");
            when(activityRepository.findByUserId("user-1")).thenReturn(List.of(a));

            StringWriter out = new StringWriter();
            exportService.writeCsv("user-1", "activities", out);

            List<String> lines = out.toString().lines().toList();
            assertThat(lines).hasSize(2);
            assertThat(lines.get(1)).startsWith("\"act-1\",\"RUNNING\",\"2026-09-20T07:30\"");
            assertThat(lines.get(1)).contains("\"Felt great\"");
        }

        @Test
        @DisplayName("an unknown dataset is a plain RuntimeException, so it becomes a 400")
        void unknownDatasetRejected() {
            StringWriter out = new StringWriter();

            // Exactly RuntimeException, not a subclass — a subclass falls through to
            // the 500 handler and gets reported as a server fault (AGENT.md §5).
            assertThatThrownBy(() -> exportService.writeCsv("user-1", "everything", out))
                    .isExactlyInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Unknown dataset")
                    .hasMessageContaining("activities");
        }

        @Test
        @DisplayName("a dead session throws AccountNotFoundException, not a plain 400")
        void deadSessionThrowsAccountNotFound() {
            when(userRepository.findById("ghost")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> exportService.writeCsv("ghost", "activities", new StringWriter()))
                    .isInstanceOf(AccountNotFoundException.class);
        }
    }

    // ── Zip ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("The zip contains one CSV per dataset, each with its headers")
    void zipContainsEveryDataset() throws Exception {
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        emptyEverything();

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        exportService.writeZip("user-1", bytes);

        List<String> entries = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(bytes.toByteArray()), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.add(entry.getName());
                // Every entry has real content — the failure this catches is closing the
                // writer per entry, which closes the whole zip stream after the first.
                assertThat(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).isNotEmpty();
            }
        }

        assertThat(entries).hasSize(ExportService.datasets().size());
        assertThat(entries).contains("fitpilot-activities.csv", "fitpilot-meals.csv",
                "fitpilot-exercise-records.csv");
    }

    // ── JSON ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("JSON export")
    class JsonExport {

        @Test
        @DisplayName("carries every dataset, even the empty ones")
        void carriesEveryDataset() {
            when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
            emptyEverything();

            Map<String, Object> document = exportService.buildJsonExport("user-1");

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) document.get("data");
            assertThat(data.keySet()).containsExactlyElementsOf(ExportService.datasets());
            assertThat(document).containsKeys("exportedAt", "schemaVersion", "profile");
        }

        @Test
        @DisplayName("never carries the password hash or the role")
        void neverCarriesSecrets() {
            when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
            emptyEverything();

            @SuppressWarnings("unchecked")
            Map<String, Object> profile =
                    (Map<String, Object>) exportService.buildJsonExport("user-1").get("profile");

            // An export is a file that gets emailed and dropped in cloud storage. The
            // hash has no business travelling with it, and the same audit is why
            // UserResponse has no password field either (AGENT.md §10).
            assertThat(profile).doesNotContainKeys("password", "role");
            assertThat(profile.values()).doesNotContain(user.getPassword());
            assertThat(profile).containsEntry("email", "runner@test.com");
        }
    }

    // ── The drift guard ──────────────────────────────────────────────────────

    /**
     * Headers are declared separately from the row builders, which invites exactly one
     * bug: a field added to a row and not to the header list is silently dropped from
     * every CSV while still appearing in the JSON. This makes that a failing test
     * instead of a column nobody notices is missing.
     */
    @Test
    @DisplayName("No row builder emits a key the headers don't declare")
    void rowKeysMatchDeclaredHeaders() {
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        emptyEverything();

        Activity a = new Activity();
        a.setId("act-1");
        a.setType(ActivityType.RUNNING);
        a.setStartTime(LocalDateTime.now());
        when(activityRepository.findByUserId("user-1")).thenReturn(List.of(a));

        Map<String, Object> document = exportService.buildJsonExport("user-1");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) document.get("data");

        for (String dataset : ExportService.datasets()) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get(dataset);
            assertThat(rows).as("dataset %s missing from the JSON export", dataset).isNotNull();

            StringWriter header = new StringWriter();
            try {
                exportService.writeCsv("user-1", dataset, header);
            } catch (Exception e) {
                throw new AssertionError("dataset " + dataset + " failed to write", e);
            }
            List<String> declared = List.of(header.toString().lines().findFirst().orElse("").split(","));

            for (Map<String, Object> row : rows) {
                assertThat(row.keySet())
                        .as("dataset %s emits a key its headers don't declare", dataset)
                        .isSubsetOf(declared);
            }
        }
    }
}
