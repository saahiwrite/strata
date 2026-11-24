package edu.neu.strata.order.release;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ReleaseProperties.class)
public class ReleaseConfig {

    @Bean
    public BrokenReleaseFilter brokenReleaseFilter(ReleaseProperties release, MeterRegistry registry) {
        return new BrokenReleaseFilter(release, registry);
    }
}
