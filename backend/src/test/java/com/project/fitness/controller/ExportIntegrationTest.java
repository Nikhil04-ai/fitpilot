package com.project.fitness.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.fitness.dto.ActivityRequest;
import com.project.fitness.dto.ExerciseRequest;
import com.project.fitness.dto.LoginRequest;
import com.project.fitness.dto.MealRequest;
import com.project.fitness.dto.RegisterRequest;
import com.project.fitness.model.ActivityType;
import com.project.fitness.model.MealType;
import com.project.fitness.service.ExportService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Data export end to end (PLAN P2-5).
 *
 * <p>Plain {@code java.net.http.HttpClient}, not TestRestTemplate or MockMvc (AGENT.md §7).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Data Export Integration Tests")
class ExportIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();

    private String jwt;

    /** A note that a spreadsheet would execute if it were exported unguarded. */
    private static final String FORMULA_NOTE = "=HYPERLINK(\"http://evil.example\",\"Click\")";

    @BeforeAll
    void setupOnce() throws Exception {
        RegisterRequest reg = new RegisterRequest();
        reg.setEmail("exporttest@test.com");
        reg.setPassword("testPass123");
        reg.setFirstName("Export");
        reg.setLastName("Tester");
        reg.setWeight(75.0);
        reg.setHeight(180.0);
        reg.setAge(28);
        assertThat(send(post("/api/auth/register", reg)).statusCode()).isEqualTo(200);

        LoginRequest login = new LoginRequest();
        login.setEmail("exporttest@test.com");
        login.setPassword("testPass123");
        jwt = json(send(post("/api/auth/login", login))).get("accessToken").asText();

        // Something in every shape the export covers.
        ActivityRequest workout = new ActivityRequest();
        workout.setType(ActivityType.WEIGHT_TRAINING);
        workout.setDuration(45);
        workout.setStartTime(LocalDateTime.now().minusDays(1));
        workout.setNotes(FORMULA_NOTE);
        workout.setExercises(List.of(new ExerciseRequest("Bench Press", 3, 8, 60.0)));
        assertThat(send(postAuth("/api/activities", workout)).statusCode()).isEqualTo(200);

        MealRequest meal = new MealRequest();
        meal.setMealType(MealType.LUNCH);
        meal.setDescription("Chicken, rice and greens");
        meal.setCalories(640);
        meal.setProteinGrams(48.0);
        assertThat(send(postAuth("/api/nutrition/meals", meal)).statusCode()).isEqualTo(200);
    }

    // ── JSON ─────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("The JSON export carries every dataset and the profile")
    void jsonExportIsComplete() throws Exception {
        HttpResponse<String> response = send(getAuth("/api/export/json"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Disposition"))
                .get().asString().contains("attachment").contains(".json");

        JsonNode body = json(response);
        assertThat(body.get("schemaVersion").asInt()).isEqualTo(1);

        JsonNode data = body.get("data");
        for (String dataset : ExportService.datasets()) {
            assertThat(data.has(dataset)).as("dataset %s missing", dataset).isTrue();
        }
        assertThat(data.get("activities")).hasSize(1);
        assertThat(data.get("exercises")).hasSize(1);
        assertThat(data.get("meals")).hasSize(1);

        // The movement carries its workout's date, so the CSV is usable on its own.
        assertThat(data.get("exercises").get(0).get("activityStartTime").asText()).isNotBlank();
        assertThat(data.get("exercises").get(0).get("estimatedOneRepMax").asDouble()).isEqualTo(76.0);
    }

    @Test
    @Order(2)
    @DisplayName("The JSON export never carries the password hash")
    void jsonExportOmitsPassword() throws Exception {
        HttpResponse<String> response = send(getAuth("/api/export/json"));

        JsonNode profile = json(response).get("profile");
        assertThat(profile.has("password")).isFalse();
        assertThat(profile.has("role")).isFalse();
        // Belt and braces on the whole document, not just the profile object — an
        // export is a file that gets emailed and dropped in cloud storage.
        assertThat(response.body()).doesNotContain("$2a$");
        assertThat(profile.get("email").asText()).isEqualTo("exporttest@test.com");
    }

    // ── CSV ──────────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("A CSV export is an attachment with headers and a UTF-8 BOM")
    void csvExportHasHeadersAndBom() throws Exception {
        HttpResponse<String> response = send(getAuth("/api/export/csv/activities"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .get().asString().contains("text/csv");
        assertThat(response.headers().firstValue("Content-Disposition"))
                .get().asString().contains("fitpilot-activities");

        // Without the BOM, Excel on Windows opens a UTF-8 CSV as the system codepage
        // and an accented note comes out as mojibake — which reads as a corrupt export.
        assertThat(response.body()).startsWith("﻿");
        assertThat(response.body()).contains("id,type,startTime");
    }

    /**
     * The finding this whole guard exists for.
     *
     * <p>A cell beginning {@code =} is executed as a formula by Excel, Sheets and
     * LibreOffice, so a workout note is a live payload in the exported spreadsheet
     * unless it is neutralised on the way out.
     */
    @Test
    @Order(4)
    @DisplayName("A note that looks like a formula is exported as text, not as a formula")
    void csvNeutralisesFormulaInjection() throws Exception {
        String body = send(getAuth("/api/export/csv/activities")).body();

        assertThat(body).contains("\"'=HYPERLINK");
        // The bare form — what a spreadsheet would run — must not be present.
        assertThat(body).doesNotContain(",\"=HYPERLINK");
    }

    @Test
    @Order(5)
    @DisplayName("An unknown dataset is a 400 naming the ones that exist, not a truncated file")
    void unknownDatasetIsBadRequest() throws Exception {
        HttpResponse<String> response = send(getAuth("/api/export/csv/everything"));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("Unknown dataset").contains("activities");
    }

    @Test
    @Order(6)
    @DisplayName("The dataset list is published so a client needs no copy of it")
    void datasetsArePublished() throws Exception {
        JsonNode body = json(send(getAuth("/api/export/datasets")));

        assertThat(body.get("datasets")).hasSize(ExportService.datasets().size());
        assertThat(body.get("formats").toString()).contains("json").contains("csv").contains("zip");
    }

    // ── Zip ──────────────────────────────────────────────────────────────────

    @Test
    @Order(7)
    @DisplayName("The zip holds one readable CSV per dataset")
    void zipHoldsEveryDataset() throws Exception {
        HttpResponse<byte[]> response = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(url("/api/export/zip")))
                        .header("Authorization", "Bearer " + jwt)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).get().asString().contains("zip");

        List<String> names = new ArrayList<>();
        String activitiesCsv = null;
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(response.body()), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                names.add(entry.getName());
                String content = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                // Every entry has its header line — the failure this catches is closing
                // the writer per entry, which closes the zip after the first one.
                assertThat(content).as("entry %s is empty", entry.getName()).isNotEmpty();
                if (entry.getName().contains("activities")) activitiesCsv = content;
            }
        }

        assertThat(names).hasSize(ExportService.datasets().size());
        assertThat(activitiesCsv).contains("WEIGHT_TRAINING");
    }

    // ── Authorization ────────────────────────────────────────────────────────

    @Test
    @Order(8)
    @DisplayName("An export contains only the caller's own data")
    void exportIsScopedToTheCaller() throws Exception {
        RegisterRequest reg = new RegisterRequest();
        reg.setEmail("exportstranger@test.com");
        reg.setPassword("testPass123");
        reg.setFirstName("Someone");
        reg.setLastName("Else");
        assertThat(send(post("/api/auth/register", reg)).statusCode()).isEqualTo(200);

        LoginRequest login = new LoginRequest();
        login.setEmail("exportstranger@test.com");
        login.setPassword("testPass123");
        String otherJwt = json(send(post("/api/auth/login", login))).get("accessToken").asText();

        JsonNode theirs = json(send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/export/json")))
                .header("Authorization", "Bearer " + otherJwt)
                .GET().build()));

        // There is no userId on these routes to tamper with; the export is whatever the
        // token says it is, and a new account's is empty.
        assertThat(theirs.get("data").get("activities")).isEmpty();
        assertThat(theirs.get("data").get("meals")).isEmpty();
        assertThat(theirs.get("profile").get("email").asText()).isEqualTo("exportstranger@test.com");
    }

    @Test
    @Order(9)
    @DisplayName("Export routes require a token at all")
    void requiresAuthentication() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/export/json"))).GET().build());

        // 401, not 403 — the frontend only attempts a silent refresh on 401 (AGENT.md §4).
        assertThat(response.statusCode()).isEqualTo(401);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        String body = response.body();
        // The CSV routes emit a BOM; the JSON ones don't, but strip defensively so a
        // parse failure here is never about an invisible character.
        if (!body.isEmpty() && body.charAt(0) == '﻿') body = body.substring(1);
        return objectMapper.readTree(body);
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpRequest post(String path, Object body) throws Exception {
        return HttpRequest.newBuilder()
                .uri(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
    }

    private HttpRequest postAuth(String path, Object body) throws Exception {
        return HttpRequest.newBuilder()
                .uri(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + jwt)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
    }

    private HttpRequest getAuth(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url(path)))
                .header("Authorization", "Bearer " + jwt)
                .GET().build();
    }
}
