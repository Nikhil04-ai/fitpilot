package com.project.fitness.controller;

import com.project.fitness.security.CurrentUser;
import com.project.fitness.service.ExportService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Data export (PLAN P2-5)  →  /api/export
 *
 * <p>No {@code {userId}} segment and no userId parameter: an export is the whole of
 * someone's account in one file, so the only id it will act on is the one in the JWT.
 *
 * <p>The CSV and zip routes write straight to the servlet response rather than building
 * the file in memory first. This is the one endpoint whose response grows without bound
 * — a few years of training, meals and sleep is the largest thing this app will ever
 * hand back — and buffering it would hold all of it on the heap of a free-tier instance.
 */
@RestController
@RequestMapping("/api/export")
@RequiredArgsConstructor
public class ExportController {

    private final ExportService exportService;

    /** What can be exported, so a client doesn't need its own copy of the list. */
    @GetMapping("/datasets")
    public ResponseEntity<Map<String, Object>> datasets() {
        return ResponseEntity.ok(Map.of(
                "datasets", ExportService.datasets(),
                "formats", List.of("json", "csv", "zip")));
    }

    /** Everything, as one JSON document. */
    @GetMapping(value = "/json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> exportJson() {
        String userId = CurrentUser.id();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment("fitpilot-export", "json"))
                .body(exportService.buildJsonExport(userId));
    }

    /** One dataset as CSV. */
    @GetMapping("/csv/{dataset}")
    public void exportCsv(@PathVariable String dataset, HttpServletResponse response) throws IOException {
        String userId = CurrentUser.id();

        // Both checks run before a single header is written: once the response is
        // committed an exception can no longer become a clean 400 or 409, and the
        // client gets a truncated file with a 200 on it instead of an error it can read.
        exportService.requireAccount(userId);
        if (!ExportService.isDataset(dataset)) {
            throw new RuntimeException("Unknown dataset: " + dataset
                    + ". Available: " + String.join(", ", ExportService.datasets()));
        }

        response.setContentType("text/csv");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, attachment("fitpilot-" + dataset, "csv"));

        try (PrintWriter writer = response.getWriter()) {
            // A BOM, so Excel on Windows opens a UTF-8 CSV as UTF-8 instead of as the
            // system codepage — without it an accented note or a "—" in a workout
            // comment comes out as mojibake, which looks like the export corrupted it.
            writer.write('﻿');
            exportService.writeCsv(userId, dataset, writer);
        }
    }

    /** Every dataset, as CSVs inside one zip. */
    @GetMapping("/zip")
    public void exportZip(HttpServletResponse response) throws IOException {
        String userId = CurrentUser.id();
        exportService.requireAccount(userId);   // before any header; see exportCsv

        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, attachment("fitpilot-export", "zip"));

        try (OutputStream out = response.getOutputStream()) {
            exportService.writeZip(userId, out);
        }
    }

    /**
     * A dated filename, so successive exports don't overwrite each other in a downloads
     * folder and the file still says what it is a month later.
     */
    private String attachment(String base, String extension) {
        return "attachment; filename=\"" + base + "-" + LocalDate.now() + "." + extension + "\"";
    }
}
