package edu.neu.strata.healer.verification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class VerificationConfigTest {

    // Spring Boot's conversion service (as in the running app) so "2s" becomes a Duration.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(VerificationConfig.class);

    @Test
    void wiresPrometheusVerifierWithDefaults() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(PrometheusClient.class);
            assertThat(context.getBean(RecoveryVerifier.class)).isInstanceOf(PrometheusRecoveryVerifier.class);
        });
    }

    @Test
    void badSettingStopsStartup() {
        runner.withPropertyValues("strata.verification.max-error-rate=2")
                .run(context -> assertThat(context).hasFailed());
    }
}
