package com.project.fitness.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
public class WeeklyDigestResponse {

    private String id;
    private LocalDate weekStart;
    private LocalDate weekEnd;

    private String headline;
    private String narrative;
    private List<String> observations;
    private List<String> focusNextWeek;

    /**
     * The figures the narrative was written from, sent so the UI can show them next to
     * it. A digest whose numbers can't be checked is a digest you have to take on faith.
     */
    private Map<String, Object> dataPoints;

    /**
     * False when the rule-based fallback wrote this because no Gemini key is configured.
     * Surfaced rather than hidden: the fallback states facts and the model interprets
     * them, and the reader is entitled to know which one they are reading.
     */
    private Boolean aiGenerated;

    private LocalDateTime createdAt;
}
