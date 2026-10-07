package com.project.fitness.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * Exercises the Gemini client against a mock HTTP server — no network, no API key.
 *
 * The service had no tests at all, which is why several of the problems below went
 * unnoticed: a retired model name, the key travelling in the query string, and an
 * empty-candidates response surfacing as a bare NullPointerException.
 */
@DisplayName("GeminiService Tests")
class GeminiServiceTest {

    private static final String BASE =
            "https://generativelanguage.googleapis.com/v1beta/models/";

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private GeminiService service(String apiKey, String model) {
        return new GeminiService(builder, objectMapper, apiKey, model);
    }

    /** Wraps `text` as the model's reply, escaping it properly via Jackson. */
    private String candidateResponse(String text) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "candidates", java.util.List.of(Map.of(
                            "content", Map.of(
                                    "parts", java.util.List.of(Map.of("text", text)))))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("Sends the key as a header and the configured model in the path")
    void sendsKeyAsHeaderAndConfiguredModel() {
        server.expect(requestTo(BASE + "gemini-2.0-flash:generateContent"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andRespond(withSuccess(
                        candidateResponse("{\"type\":\"MOTIVATION\"}"),
                        MediaType.APPLICATION_JSON));

        Map<String, Object> result = service("test-key", "gemini-2.0-flash")
                .generateRecommendation("coach me");

        server.verify();
        assertThat(result).containsEntry("type", "MOTIVATION");
    }

    @Test
    @DisplayName("A different configured model changes the URL — no code change needed")
    void modelIsConfigurable() {
        server.expect(requestTo(BASE + "gemini-2.5-flash:generateContent"))
                .andRespond(withSuccess(
                        candidateResponse("{\"type\":\"SAFETY\"}"),
                        MediaType.APPLICATION_JSON));

        service("test-key", "gemini-2.5-flash").generateRecommendation("coach me");

        server.verify();
    }

    @Test
    @DisplayName("Strips the ```json fences models wrap JSON in")
    void stripsMarkdownFences() {
        server.expect(requestTo(BASE + "gemini-2.0-flash:generateContent"))
                .andRespond(withSuccess(
                        candidateResponse("```json\n{\"type\":\"RECOVERY\"}\n```"),
                        MediaType.APPLICATION_JSON));

        Map<String, Object> result = service("test-key", "gemini-2.0-flash")
                .generateRecommendation("coach me");

        assertThat(result).containsEntry("type", "RECOVERY");
    }

    /**
     * Gemini returns 200 with an empty candidates array when a prompt is blocked.
     * Indexing straight into candidates[0] made that a NullPointerException; callers
     * expect null so they can fall back to rule-based advice.
     */
    @Test
    @DisplayName("Returns null (not an exception) when the response has no candidates")
    void handlesBlockedResponse() {
        server.expect(requestTo(BASE + "gemini-2.0-flash:generateContent"))
                .andRespond(withSuccess(
                        "{\"candidates\":[],\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}",
                        MediaType.APPLICATION_JSON));

        assertThat(service("test-key", "gemini-2.0-flash").generateRecommendation("x")).isNull();
    }

    @Test
    @DisplayName("Returns null when a candidate carries no content parts")
    void handlesCandidateWithoutParts() {
        server.expect(requestTo(BASE + "gemini-2.0-flash:generateContent"))
                .andRespond(withSuccess(
                        "{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\",\"content\":{}}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(service("test-key", "gemini-2.0-flash").generateRecommendation("x")).isNull();
    }

    @Test
    @DisplayName("An HTTP error degrades to null rather than propagating")
    void handlesHttpError() {
        server.expect(requestTo(BASE + "gemini-2.0-flash:generateContent"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .body("{\"error\":{\"message\":\"Quota exceeded\"}}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThat(service("test-key", "gemini-2.0-flash").generateRecommendation("x")).isNull();
    }

    @Test
    @DisplayName("Non-JSON text in the response degrades to null")
    void handlesUnparseableText() {
        server.expect(requestTo(BASE + "gemini-2.0-flash:generateContent"))
                .andRespond(withSuccess(
                        candidateResponse("I'm sorry, I can't help with that."),
                        MediaType.APPLICATION_JSON));

        assertThat(service("test-key", "gemini-2.0-flash").generateRecommendation("x")).isNull();
    }

    @Test
    @DisplayName("With no API key configured, no HTTP call is made at all")
    void noKeyMeansNoCall() {
        GeminiService noKey = service("", "gemini-2.0-flash");

        assertThat(noKey.isConfigured()).isFalse();
        assertThat(noKey.generateRecommendation("x")).isNull();
        assertThat(noKey.parseNaturalLanguageActivity("I ran 5km")).isNull();

        server.verify();   // no requests were expected, and none were made
    }

    @Test
    @DisplayName("Natural language parsing returns the parsed activity fields")
    void parsesNaturalLanguageActivity() {
        server.expect(requestTo(BASE + "gemini-2.0-flash:generateContent"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("I ran 5km")))
                .andRespond(withSuccess(
                        candidateResponse("{\"type\":\"RUNNING\",\"durationMinutes\":30}"),
                        MediaType.APPLICATION_JSON));

        Map<String, Object> parsed = service("test-key", "gemini-2.0-flash")
                .parseNaturalLanguageActivity("I ran 5km");

        assertThat(parsed).containsEntry("type", "RUNNING");
        assertThat(parsed).containsEntry("durationMinutes", 30);
    }
}
