package com.project.fitness.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers a Jackson 2 {@code ObjectMapper} as a Spring bean, for the code that injects
 * one by hand: {@code GeminiService}, {@code WeeklyDigestService} and
 * {@code RestClientConfig}. Spring Boot 4.1 no longer auto-exposes that type.
 *
 * <p><b>This is not what the web layer uses</b> (PLAN P4-20, verified). Spring MVC reads
 * and writes request and response bodies with Boot's own Jackson 3 mapper
 * ({@code tools.jackson}'s {@code JsonMapper}, behind a
 * {@code JacksonJsonHttpMessageConverter}), which this class doesn't touch. This comment
 * used to say that the settings below keep unknown request fields from being a 400 and
 * write dates as ISO strings. Both are true of the API, but because of Jackson 3's
 * defaults, not of this bean; deleting these lines would not change a response.
 * {@code LeftoversIntegrationTest} pins both behaviours at the HTTP level.
 *
 * <p>What the settings do, for the injected mapper only:
 * <ul>
 *   <li>JavaTimeModule, and {@code WRITE_DATES_AS_TIMESTAMPS} off: dates it writes are
 *       ISO strings ({@code "2026-06-23T07:00:00"}), not arrays.</li>
 *   <li>{@code FAIL_ON_UNKNOWN_PROPERTIES} off: a hand-built mapper has it on, so it
 *       would reject any extra field in the JSON Gemini returns.</li>
 * </ul>
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }
}
