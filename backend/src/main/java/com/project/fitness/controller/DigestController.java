package com.project.fitness.controller;

import com.project.fitness.dto.WeeklyDigestResponse;
import com.project.fitness.security.AccountGuard;
import com.project.fitness.security.CurrentUser;
import com.project.fitness.service.WeeklyDigestService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * The weekly digest (PLAN P2-3)  →  /api/digest
 *
 * <p>Identity from the JWT throughout; there is no userId segment to tamper with.
 */
@RestController
@RequestMapping("/api/digest")
@RequiredArgsConstructor
public class DigestController {

    private final WeeklyDigestService weeklyDigestService;
    private final AccountGuard accountGuard;

    /** The most recent digest, or 204 when none has been generated yet. */
    @GetMapping
    public ResponseEntity<WeeklyDigestResponse> latest() {
        WeeklyDigestResponse latest = weeklyDigestService.getLatest(accountGuard.requireCurrent());
        return latest == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(latest);
    }

    @GetMapping("/history")
    public ResponseEntity<List<WeeklyDigestResponse>> history() {
        return ResponseEntity.ok(weeklyDigestService.getHistory(accountGuard.requireCurrent()));
    }

    /**
     * Builds the digest on demand. Defaults to the week just finished.
     *
     * <p>Rate-limited on the AI tier: each call is a Gemini request against a shared
     * quota, and it reasons over a whole week rather than five activities, so it is the
     * most expensive prompt the app sends.
     */
    @PostMapping("/generate")
    public ResponseEntity<WeeklyDigestResponse> generate(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart) {
        return ResponseEntity.ok(weeklyDigestService.generate(CurrentUser.id(), weekStart));
    }
}
