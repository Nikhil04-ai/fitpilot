package com.project.fitness.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * The email transport against a mock HTTP server — no network, no API key.
 *
 * <p>Password reset reports success to the requester whether or not anything was sent
 * (it must not reveal which addresses have accounts), so a broken transport is
 * invisible from the UI. These tests are the only thing that notices.
 */
@DisplayName("EmailService Tests")
class EmailServiceTest {

    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
    }

    private EmailService service(String provider, String key, String from) {
        return new EmailService(builder, provider, key, "", from);
    }

    @Test
    @DisplayName("Brevo: api-key header, sender split from the display name, to as a list")
    void brevoRequestShape() {
        server.expect(requestTo(EmailService.BREVO_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("api-key", "brevo-key"))
                .andExpect(jsonPath("$.sender.email").value("coach@gmail.com"))
                .andExpect(jsonPath("$.sender.name").value("FitPilot"))
                .andExpect(jsonPath("$.to[0].email").value("user@example.com"))
                .andExpect(jsonPath("$.subject").value("Reset your FitPilot password"))
                .andExpect(jsonPath("$.htmlContent").value("<p>hi</p>"))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"messageId\":\"<abc@smtp-relay.mailin.fr>\"}"));

        boolean sent = service("brevo", "brevo-key", "FitPilot <coach@gmail.com>")
                .send("user@example.com", "Reset your FitPilot password", "<p>hi</p>");

        assertThat(sent).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("Brevo: a bare sender address is sent without a name")
    void brevoBareSender() {
        server.expect(requestTo(EmailService.BREVO_URL))
                .andExpect(jsonPath("$.sender.email").value("coach@gmail.com"))
                .andExpect(jsonPath("$.sender.name").doesNotExist())
                .andRespond(withStatus(HttpStatus.CREATED));

        assertThat(service("brevo", "k", "coach@gmail.com").send("u@example.com", "s", "b")).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("Resend: bearer auth and its own body shape")
    void resendRequestShape() {
        server.expect(requestTo(EmailService.RESEND_URL))
                .andExpect(header("Authorization", "Bearer re_key"))
                .andExpect(jsonPath("$.from").value("FitPilot <onboarding@resend.dev>"))
                .andExpect(jsonPath("$.to[0]").value("user@example.com"))
                .andExpect(jsonPath("$.html").value("<p>hi</p>"))
                .andRespond(withSuccess("{\"id\":\"1\"}", MediaType.APPLICATION_JSON));

        assertThat(service("resend", "re_key", "FitPilot <onboarding@resend.dev>")
                .send("user@example.com", "s", "<p>hi</p>")).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("A provider refusal (e.g. unverified sender) is false, not an exception")
    void providerRefusalIsFalse() {
        server.expect(requestTo(EmailService.BREVO_URL))
                .andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"invalid_parameter\",\"message\":\"sender is not valid\"}"));

        assertThat(service("brevo", "k", "coach@gmail.com").send("u@example.com", "s", "b")).isFalse();
        server.verify();
    }

    @Test
    @DisplayName("No key: nothing is sent and the caller hears false")
    void unconfiguredSendsNothing() {
        server.expect(never(), requestTo(EmailService.BREVO_URL));

        EmailService email = service("brevo", "", "coach@gmail.com");

        assertThat(email.isConfigured()).isFalse();
        assertThat(email.send("u@example.com", "s", "b")).isFalse();
        server.verify();
    }

    @Test
    @DisplayName("A key with no sender is refused before any request is made")
    void missingSenderRefusedLocally() {
        server.expect(never(), requestTo(EmailService.BREVO_URL));

        EmailService email = service("brevo", "k", "");

        assertThat(email.configurationProblem()).contains("MAIL_FROM is not set");
        assertThat(email.send("u@example.com", "s", "b")).isFalse();
        server.verify();
    }

    @Test
    @DisplayName("Brevo with Resend's test sender is caught — Brevo would refuse it")
    void brevoWithResendSenderIsCaught() {
        EmailService email = service("brevo", "k", "FitPilot <onboarding@resend.dev>");

        assertThat(email.configurationProblem()).contains("Resend's test address");
        assertThat(email.send("u@example.com", "s", "b")).isFalse();
    }

    @Test
    @DisplayName("An unknown provider name is reported, not guessed at")
    void unknownProviderIsReported() {
        assertThat(service("sendgrid", "k", "a@b.com").configurationProblem())
                .contains("must be 'brevo' or 'resend'");
    }

    @Test
    @DisplayName("Provider name is case-insensitive and a correct setup has no problem")
    void wellConfiguredHasNoProblem() {
        EmailService email = service(" Brevo ", "k", "FitPilot <coach@gmail.com>");

        assertThat(email.configurationProblem()).isNull();
        assertThat(email.senderAddress()).isEqualTo("coach@gmail.com");
        assertThat(email.senderName()).isEqualTo("FitPilot");
    }
}
