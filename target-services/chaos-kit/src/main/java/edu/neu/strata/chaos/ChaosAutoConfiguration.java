package edu.neu.strata.chaos;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.TimeUnit;

/**
 * Adds the failure switches to any Spring Boot web service that has chaos-kit on its classpath:
 * the {@code /chaos} endpoints, the filter that applies the error and slow switches, the
 * memory switch and their metrics. Turn it off with {@code strata.chaos.enabled=false}.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "strata.chaos", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
@Import(ChaosController.class)
public class ChaosAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ChaosState chaosState(MeterRegistry registry) {
        return new ChaosState(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryLeak memoryLeak(MeterRegistry registry) {
        return new MemoryLeak(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChaosFilter chaosFilter(ChaosState state, MeterRegistry registry) {
        return new ChaosFilter(state, registry);
    }

    /**
     * Halts the JVM shortly after the crash request returns, so the caller still gets a
     * response. {@code halt} skips shutdown hooks on purpose: the process dies the way a real
     * crash would, and the container exits with a non-zero code.
     */
    @Bean
    @ConditionalOnMissingBean
    public Terminator terminator() {
        return exitCode -> {
            Thread thread = new Thread(() -> {
                try {
                    TimeUnit.MILLISECONDS.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                Runtime.getRuntime().halt(exitCode);
            }, "chaos-crash");
            thread.setDaemon(false);
            thread.start();
        };
    }
}
