package edu.neu.strata.inventory.chaos;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableScheduling
public class ChaosConfig {

    /**
     * Halts the JVM shortly after the crash request returns, so the caller still gets a
     * response. {@code halt} skips shutdown hooks on purpose: the process dies the way a real
     * crash would, and the container exits with a non-zero code.
     */
    @Bean
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
