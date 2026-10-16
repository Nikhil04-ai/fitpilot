package com.project.fitness.config;

import com.project.fitness.service.EmailService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

/**
 * Refuses to start the prod profile without the settings a deployed instance needs
 * (PLAN P5-15, P5-16).
 *
 * <p>Locally both are optional on purpose: without a mail key the reset link is written
 * to the log, and without FRONTEND_URL it points at the local dev server. Deployed, each
 * of those fails silently. A link in the log is a password equivalent in a log more
 * people can read than the account's owner, and a link to localhost is dead. The reset
 * form says "a reset link is on its way" either way, because it must not reveal which
 * addresses have accounts, so nobody would find out from the page. A deploy that fails
 * with the reason in its log is found at once.
 */
@Component
@Profile("prod")
public class ProductionSettingsCheck {

    public ProductionSettingsCheck(@Value("${app.frontend-url:}") String frontendUrl, EmailService emailService) {
        List<String> problems = new ArrayList<>();

        String frontendProblem = frontendUrlProblem(frontendUrl);
        if (frontendProblem != null) problems.add(frontendProblem);

        if (!emailService.isConfigured()) {
            problems.add("MAIL_API_KEY is not set. Without it, password reset links would be "
                    + "written to the log instead of emailed. See README > Password reset email.");
        } else {
            String mailProblem = emailService.configurationProblem();
            if (mailProblem != null) problems.add(mailProblem);
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("The prod profile can't start: " + String.join(" ", problems));
        }
    }

    /** Null when FRONTEND_URL is an https address with a host, otherwise why it isn't. */
    static String frontendUrlProblem(String frontendUrl) {
        if (frontendUrl == null || frontendUrl.isBlank()) {
            return "FRONTEND_URL is not set. It is the only origin allowed to call this API "
                    + "from a browser, and where reset links point.";
        }
        try {
            URI uri = new URI(frontendUrl.trim());
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null) return null;
        } catch (URISyntaxException e) {
            // Reported below.
        }
        return "FRONTEND_URL must be the deployed frontend's https address, "
                + "e.g. https://name.github.io/repo, not '" + frontendUrl.trim() + "'.";
    }
}
