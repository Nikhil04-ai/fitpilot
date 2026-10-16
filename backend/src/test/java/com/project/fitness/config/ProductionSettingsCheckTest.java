package com.project.fitness.config;

import com.project.fitness.service.EmailService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The prod profile doesn't start without FRONTEND_URL and working mail (PLAN P5-15, P5-16).
 *
 * <p>A real, minimal context: just the check and the {@link EmailService} it asks, with
 * settings given as properties, so "refuses to start" is tested as a failed startup.
 */
@DisplayName("ProductionSettingsCheck")
class ProductionSettingsCheckTest {

    private static final String FRONTEND = "app.frontend-url=https://name.github.io/fitpilot";
    private static final String KEY = "app.mail.api-key=xkeysib-test";
    private static final String FROM = "app.mail.from=FitPilot <sender@example.com>";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withUserConfiguration(EmailService.class, ProductionSettingsCheck.class);

    private ApplicationContextRunner prod(String... properties) {
        return runner.withPropertyValues("spring.profiles.active=prod").withPropertyValues(properties);
    }

    @Test
    @DisplayName("starts with an https FRONTEND_URL, a mail key and a sender")
    void startsWhenEverythingIsSet() {
        prod(FRONTEND, KEY, FROM).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ProductionSettingsCheck.class);
        });
    }

    @Test
    @DisplayName("refuses to start with nothing set, and names both missing variables")
    void refusesWithNothingSet() {
        prod().run(context -> assertThat(context).getFailure()
                .rootCause()
                .hasMessageContaining("FRONTEND_URL is not set")
                .hasMessageContaining("MAIL_API_KEY is not set"));
    }

    @Test
    @DisplayName("refuses a FRONTEND_URL that isn't https")
    void refusesPlainHttpFrontend() {
        prod("app.frontend-url=http://localhost:5500", KEY, FROM).run(context ->
                assertThat(context).getFailure().rootCause()
                        .hasMessageContaining("FRONTEND_URL must be the deployed frontend's https address"));
    }

    @Test
    @DisplayName("refuses a mail key with no sender, with EmailService's own explanation")
    void refusesKeyWithoutSender() {
        prod(FRONTEND, KEY).run(context ->
                assertThat(context).getFailure().rootCause().hasMessageContaining("MAIL_FROM is not set"));
    }

    @Test
    @DisplayName("is not there at all outside the prod profile, so local runs need neither")
    void absentOutsideProd() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ProductionSettingsCheck.class);
        });
    }
}
