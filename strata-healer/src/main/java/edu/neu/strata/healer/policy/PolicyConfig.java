package edu.neu.strata.healer.policy;

import java.io.IOException;
import java.io.InputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

/**
 * Loads the policy once at startup and exposes the {@link RiskScorer}. The policy comes from
 * {@code strata.policy.location} (any Spring resource, e.g. {@code file:/etc/strata/policy.yaml}),
 * by default the bundled {@code classpath:policy.yaml}. A bad policy stops the healer from starting.
 */
@Configuration
public class PolicyConfig {

    @Bean
    public Policy policy(@Value("${strata.policy.location:classpath:policy.yaml}") Resource location) {
        try (InputStream in = location.getInputStream()) {
            return new PolicyLoader().load(in, location.getDescription());
        } catch (IOException e) {
            throw new PolicyException("could not read policy " + location.getDescription(), e);
        }
    }

    @Bean
    public RiskScorer riskScorer(Policy policy) {
        return new RiskScorer(policy);
    }
}
