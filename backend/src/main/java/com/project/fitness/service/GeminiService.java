package com.project.fitness.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

/**
 * Feature 9 + 10 — Gemini API integration
 *
 * Setup:
 *  1. Get a FREE key at https://aistudio.google.com/app/apikey
 *  2. Set GEMINI_API_KEY in the environment (IntelliJ: Run → Edit Configurations →
 *     Environment Variables; Render: the service's Environment tab).
 *  3. The app works WITHOUT this key — Features 9/10 degrade gracefully: natural
 *     language logging reports that it's unavailable, and AI coaching falls back to
 *     rule-based advice (see RecommendationService).
 *
 * The model is configurable via `gemini.api.model` (env: GEMINI_MODEL) so a retired
 * model can be swapped without a code change. gemini-1.5-flash and then gemini-2.0-flash
 * were both retired under this app, each time silently turning every AI feature into
 * its fallback — hence the default is now an alias Google keeps current.
 */
@Slf4j
@Service
public class GeminiService {

    @Value("${gemini.api.key:}")
    private String apiKey;

    @Value("${gemini.api.model:gemini-flash-lite-latest}")
    private String model;

    private static final String GEMINI_BASE =
            "https://generativelanguage.googleapis.com/v1beta/models/";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    /** Timeouts and the client implementation are configured on the builder — see RestClientConfig. */
    @Autowired
    public GeminiService(RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    /** Test seam: lets a test supply its own key/model without a Spring context. */
    GeminiService(RestClient.Builder restClientBuilder, ObjectMapper objectMapper,
                  String apiKey, String model) {
        this(restClientBuilder, objectMapper);
        this.apiKey = apiKey;
        this.model = model;
    }

    /** True when a key is configured — callers use this to decide whether to bother. */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public Map<String, Object> parseNaturalLanguageActivity(String userInput) {
        if (!isConfigured()) {
            log.warn("GEMINI_API_KEY not set — natural language parsing unavailable.");
            return null;
        }

        // "exercises" is the part that matters for lifting: sets and reps as two loose
        // numbers could never describe "bench 3x8 then squat 5x5", which is what people
        // actually type (PLAN P1-1). distance_km stays where it is so older clients and
        // stored rows keep working; ActivityService promotes it to its column.
        String prompt = String.format("""
                Parse this fitness activity description into JSON only, no explanation:
                "%s"

                Return exactly this structure:
                {
                  "type": "RUNNING|WALKING|CYCLING|SWIMMING|WEIGHT_TRAINING|YOGA|HIIT|CARDIO|STRETCHING|OTHER",
                  "durationMinutes": number,
                  "distanceKm": number or null,
                  "exercises": [
                    { "name": "string", "sets": number or null, "reps": number or null, "weightKg": number or null }
                  ],
                  "additionalMetrics": {
                    "distance_km": number or null,
                    "notes": "string or null"
                  }
                }

                Rules:
                - "exercises" is for resistance movements only (bench press, squat, curls).
                  Return [] for cardio such as running, cycling or swimming.
                - Weights in lbs must be converted to kg.
                - Never invent a number that was not stated. Use null.
                """, userInput);

        return callAndParseJson(prompt, "natural language");
    }

    /**
     * Turns "two eggs and toast" into calories and macros (PLAN P2-1).
     *
     * <p><b>This is a guess and the caller treats it as one.</b> There is no food
     * database behind it, so the numbers are the model's recollection of typical
     * portions. {@code MealLog.aiEstimated} is set on anything that comes through here
     * precisely so the UI can mark it and the user can correct it.
     *
     * <p>Returns null when no key is configured, the same as every other call here —
     * the caller reports that manual entry is the way forward rather than failing.
     */
    public Map<String, Object> parseMeal(String userInput) {
        if (!isConfigured()) {
            log.warn("GEMINI_API_KEY not set — meal parsing unavailable.");
            return null;
        }

        String prompt = String.format("""
                Estimate the nutrition of this meal. Reply with JSON only, no explanation:
                "%s"

                Return exactly this structure:
                {
                  "mealType": "BREAKFAST|LUNCH|DINNER|SNACK|OTHER",
                  "calories": number,
                  "proteinGrams": number or null,
                  "carbsGrams": number or null,
                  "fatGrams": number or null
                }

                Rules:
                - Estimate typical portion sizes when none is stated.
                - Use the stated quantity when there is one.
                - "calories" is kcal for the whole meal described, not per item.
                - Infer mealType from the food if the user didn't say; OTHER if unclear.
                - Macros are grams. Return null for a macro you cannot estimate rather
                  than guessing 0.
                """, userInput);

        return callAndParseJson(prompt, "meal");
    }

    public Map<String, Object> generateRecommendation(String contextPrompt) {
        if (!isConfigured()) {
            log.warn("GEMINI_API_KEY not set — AI recommendations unavailable.");
            return null;
        }
        return callAndParseJson(contextPrompt, "recommendation");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callAndParseJson(String prompt, String what) {
        String raw = callGemini(prompt);
        if (raw == null) return null;

        try {
            String cleaned = raw.replaceAll("```json|```", "").trim();
            return objectMapper.readValue(cleaned, Map.class);
        } catch (Exception e) {
            log.error("Failed to parse Gemini {} response: {}", what, e.getMessage());
            return null;
        }
    }

    private String callGemini(String prompt) {
        try {
            Map<String, Object> requestBody = Map.of(
                    "contents", List.of(
                            Map.of("parts", List.of(Map.of("text", prompt)))
                    )
            );

            String response = restClient
                    .post()
                    .uri(GEMINI_BASE + model + ":generateContent")
                    // The key goes in a header, not "?key=..." — query strings end up in
                    // access logs, proxy logs and error reports, which is a bad place for
                    // a credential to live.
                    .header("x-goog-api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            if (response == null) return null;
            return extractText(response);

        } catch (RestClientResponseException e) {
            // Gemini explains refusals in the response body (unknown model name, bad
            // key, quota exhausted). Logging only the status turned every one of those
            // into the same unhelpful line.
            log.error("Gemini API returned {} for model '{}': {}",
                    e.getStatusCode(), model, e.getResponseBodyAsString());
            return null;
        } catch (Exception e) {
            log.error("Gemini API call failed ({}): {}", model, e.toString());
            return null;
        }
    }

    /**
     * Pulls the generated text out of the response.
     *
     * Reads defensively: a response with no candidates is a normal outcome (the prompt
     * was blocked by a safety filter, or the quota is exhausted), and indexing straight
     * into candidates[0] turned that into a NullPointerException that said nothing
     * about the real cause.
     */
    private String extractText(String response) throws Exception {
        JsonNode root = objectMapper.readTree(response);

        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            log.error("Gemini returned no candidates — blockReason={}, response={}",
                    root.path("promptFeedback").path("blockReason").asText("none"),
                    response.length() > 500 ? response.substring(0, 500) + "…" : response);
            return null;
        }

        JsonNode parts = candidates.get(0).path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            log.error("Gemini candidate had no content parts (finishReason={})",
                    candidates.get(0).path("finishReason").asText("unknown"));
            return null;
        }

        String text = parts.get(0).path("text").asText(null);
        return (text == null || text.isBlank()) ? null : text;
    }
}
