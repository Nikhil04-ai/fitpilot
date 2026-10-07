package com.project.fitness.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers ObjectMapper as a Spring bean.
 * Spring Boot 4.1 does not auto-expose ObjectMapper for injection —
 * same issue we had with WebClient.Builder. This fixes it.
 *
 * Also configures:
 *  - JavaTimeModule: so LocalDateTime / LocalDate serialize as ISO strings, not arrays
 *  - WRITE_DATES_AS_TIMESTAMPS disabled: "2026-06-23T07:00:00" not [2026,6,23,7,0,0]
 *  - FAIL_ON_UNKNOWN_PROPERTIES disabled: a hand-built ObjectMapper has this ON,
 *    unlike the one Spring Boot normally auto-configures. With it on, any extra
 *    field in a request body is a hard 400 — so removing a field from a DTO (as
 *    was done with RegisterRequest.role, which allowed self-registering as an
 *    admin) breaks every client that still sends it, with an error that points at
 *    JSON parsing rather than at what actually changed. Off, an ignored field is
 *    genuinely ignored, which is what the controllers document.
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
