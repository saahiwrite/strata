package edu.neu.strata.healer.verification;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring beans for recovery checks. Settings (defaults in brackets):
 * {@code strata.prometheus.url} [http://prometheus:9090], {@code strata.prometheus.timeout} [2s],
 * {@code strata.verification.poll-interval} [2s], {@code strata.verification.required-healthy-checks} [3],
 * {@code strata.verification.max-error-rate} [0.2, same as the HighErrorRate alert].
 */
@Configuration
public class VerificationConfig {

    @Bean
    public PrometheusClient prometheusClient(
            @Value("${strata.prometheus.url:http://prometheus:9090}") String url,
            @Value("${strata.prometheus.timeout:2s}") Duration timeout) {
        return PrometheusClient.create(url, timeout);
    }

    @Bean
    public RecoveryVerifier recoveryVerifier(
            PrometheusClient prometheusClient,
            @Value("${strata.verification.poll-interval:2s}") Duration pollInterval,
            @Value("${strata.verification.required-healthy-checks:3}") int requiredHealthyChecks,
            @Value("${strata.verification.max-error-rate:0.2}") double maxErrorRate) {
        return new PrometheusRecoveryVerifier(prometheusClient, pollInterval, requiredHealthyChecks, maxErrorRate);
    }
}
