package com.project.fitness;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.scheduling.annotation.EnableScheduling;

import static org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO;

@SpringBootApplication
@EnableScheduling   // Feature 7: enables @Scheduled for weekly plateau scan
@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)
// ↑ Phase 4: Serializes Page responses as stable JSON via PagedModel DTO
//   Suppresses: "Serializing PageImpl instances as-is is not supported" warning
public class FitnessMonolithApplication {

    public static void main(String[] args) {
        SpringApplication.run(FitnessMonolithApplication.class, args);
    }
}
