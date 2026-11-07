package edu.neu.strata.healer.execution;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Stops us from restarting the same container over and over
@Component
public class CooldownGuard {

    private final Duration cooldown;
    private final Clock clock;
    private final Map<String, Instant> lastRun = new ConcurrentHashMap<>();

    @Autowired
    public CooldownGuard(@Value("${strata.cooldown.seconds:60}") long seconds) {
        this(Duration.ofSeconds(seconds), Clock.systemUTC());
    }

    public CooldownGuard(Duration cooldown, Clock clock) {
        this.cooldown = cooldown;
        this.clock = clock;
    }

    public boolean isCoolingDown(String target) {
        Instant last = lastRun.get(target);
        return last != null && clock.instant().isBefore(last.plus(cooldown));
    }

    public void markExecuted(String target) {
        lastRun.put(target, clock.instant());
    }
}
