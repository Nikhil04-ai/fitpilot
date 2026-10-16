package com.project.fitness.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one outbound email path (PLAN P1-4).
 *
 * <p><b>Why HTTP and not SMTP.</b> Render's free tier blocks outbound SMTP ports, so
 * {@code spring-boot-starter-mail} would compile, pass tests and then silently fail to
 * deliver anything on the deployed instance. A plain HTTPS POST works everywhere, and
 * it reuses the {@link RestClient} already configured for the Gemini call rather than
 * adding another transport (AGENT.md rule 9).
 *
 * <p><b>Two providers</b>, chosen by {@code app.mail.provider} (env {@code MAIL_PROVIDER}):
 * <ul>
 *   <li>{@code brevo} — sends to any address once the sender email has been verified
 *       in the Brevo dashboard, with no domain of your own needed. The default for this
 *       reason: it is the only free option that can reach every user of a project
 *       without a domain.</li>
 *   <li>{@code resend} — without a verified domain, Resend only delivers to the address
 *       its account was opened with, so every other user's reset email is refused.</li>
 * </ul>
 *
 * <p><b>Unconfigured is a supported state.</b> With no API key the reset link is
 * written to the log instead of sent, exactly as Gemini degrades without its key — so
 * the whole flow is usable in local development, and a missing key never turns into a
 * 500 for the person asking to reset their password. It is announced once at startup:
 * the reset form reports success either way (it must not reveal whether an address
 * has an account), so a quietly disabled transport looked exactly like a working one.
 */
@Slf4j
@Service
public class EmailService {

    static final String BREVO_URL = "https://api.brevo.com/v3/smtp/email";
    static final String RESEND_URL = "https://api.resend.com/emails";

    /** "FitPilot <you@example.com>" or a bare address. */
    private static final Pattern NAMED_ADDRESS = Pattern.compile("^\\s*(.*?)\\s*<\\s*([^>]+?)\\s*>\\s*$");

    @Value("${app.mail.provider:brevo}")
    private String provider;

    @Value("${app.mail.api-key:}")
    private String apiKey;

    /** Blank means the provider's own endpoint. */
    @Value("${app.mail.api-url:}")
    private String apiUrl;

    @Value("${app.mail.from:}")
    private String from;

    private final RestClient restClient;

    @Autowired
    public EmailService(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    /** Test seam: lets a test supply its own settings without a Spring context. */
    EmailService(RestClient.Builder restClientBuilder, String provider, String apiKey, String apiUrl, String from) {
        this(restClientBuilder);
        this.provider = provider;
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
        this.from = from;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * One line at startup saying whether email works, so "the reset link never
     * arrived" is answered by the log rather than by guesswork.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reportStatus() {
        if (!isConfigured()) {
            log.warn("Email is OFF (MAIL_API_KEY not set): password reset links will be written "
                    + "to this log instead of emailed. See README > Password reset email.");
            return;
        }
        String problem = configurationProblem();
        if (problem != null) {
            log.error("Email is misconfigured: {}", problem);
        } else {
            log.info("Email is ON via {} from {}", providerName(), senderAddress());
        }
    }

    /**
     * Sends one email.
     *
     * @return true if the provider accepted it. False is never fatal to the caller:
     *         password reset deliberately reports success to the requester either
     *         way, because whether an address exists is not something this API tells
     *         an anonymous caller.
     */
    public boolean send(String to, String subject, String html) {
        if (!isConfigured()) {
            // Not an error. This is the documented local-development path.
            log.warn("app.mail.api-key not set - email not sent. Subject: {}, to: {}", subject, to);
            return false;
        }
        String problem = configurationProblem();
        if (problem != null) {
            log.error("Email not sent to {}: {}", to, problem);
            return false;
        }

        try {
            RestClient.RequestBodySpec request = restClient.post()
                    .uri(endpoint())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON);

            if (isBrevo()) {
                String name = senderName();
                Map<String, Object> sender = name == null
                        ? Map.of("email", senderAddress())
                        : Map.of("email", senderAddress(), "name", name);
                request.header("api-key", apiKey)
                        .body(Map.of(
                                "sender", sender,
                                "to", List.of(Map.of("email", to)),
                                "subject", subject,
                                "htmlContent", html));
            } else {
                request.header("Authorization", "Bearer " + apiKey)
                        .body(Map.of("from", from.trim(), "to", new String[]{to}, "subject", subject, "html", html));
            }

            request.retrieve().toBodilessEntity();
            log.info("Email '{}' accepted by {} for {}", subject, providerName(), to);
            return true;

        } catch (RestClientResponseException e) {
            // The provider explains refusals in the body (unverified sender, bad key,
            // quota). Logging only the status makes every one of those look like the
            // same unhelpful failure.
            log.error("Email provider {} returned {}: {}", providerName(), e.getStatusCode(), e.getResponseBodyAsString());
            return false;
        } catch (Exception e) {
            log.error("Email send via {} failed: {}", providerName(), e.toString());
            return false;
        }
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    /**
     * Why a configured key still can't send, or null when it can. Checked before
     * every send as well as at startup, because both failures below are silent at
     * the provider end — the request is refused and nothing is ever delivered.
     */
    public String configurationProblem() {
        String p = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        if (!p.equals("brevo") && !p.equals("resend")) {
            return "MAIL_PROVIDER must be 'brevo' or 'resend', not '" + provider + "'.";
        }
        if (senderAddress() == null) {
            return "MAIL_FROM is not set. Use the sender address verified with " + providerName()
                    + ", e.g. MAIL_FROM=FitPilot <you@gmail.com>.";
        }
        if (isBrevo() && senderAddress().toLowerCase(Locale.ROOT).endsWith("@resend.dev")) {
            return "MAIL_FROM is Resend's test address, which Brevo will refuse. "
                    + "Use the sender email you verified in Brevo.";
        }
        return null;
    }

    private boolean isBrevo() {
        return provider != null && provider.trim().equalsIgnoreCase("brevo");
    }

    private String providerName() {
        return isBrevo() ? "Brevo" : "Resend";
    }

    private String endpoint() {
        if (apiUrl != null && !apiUrl.isBlank()) return apiUrl.trim();
        return isBrevo() ? BREVO_URL : RESEND_URL;
    }

    /** The address part of MAIL_FROM, or null when it is blank. */
    String senderAddress() {
        if (from == null || from.isBlank()) return null;
        Matcher m = NAMED_ADDRESS.matcher(from);
        return m.matches() ? m.group(2) : from.trim();
    }

    /** The display-name part of MAIL_FROM, or null when there isn't one. */
    String senderName() {
        if (from == null) return null;
        Matcher m = NAMED_ADDRESS.matcher(from);
        return m.matches() && !m.group(1).isBlank() ? m.group(1) : null;
    }
}
