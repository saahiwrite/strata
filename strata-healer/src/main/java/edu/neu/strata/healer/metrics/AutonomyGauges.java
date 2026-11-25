package edu.neu.strata.healer.metrics;

import org.springframework.stereotype.Component;

import edu.neu.strata.healer.model.ActionType;
import edu.neu.strata.healer.trust.TrustService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * Each action's earned-autonomy standing (#54) as gauges, read from the trust record whenever
 * Prometheus scrapes:
 * <ul>
 *   <li>{@code strata_autonomy_level_shift{action}}: levels below (negative, promoted) or above
 *       (positive, demoted) what the risk score alone gives</li>
 *   <li>{@code strata_autonomy_success_streak{action}}: successes in a row towards the next promotion</li>
 * </ul>
 * Read on scrape rather than kept in memory, so the gauges are right from the first scrape after a restart.
 */
@Component
public class AutonomyGauges implements MeterBinder {

    private final TrustService trust;

    public AutonomyGauges(TrustService trust) {
        this.trust = trust;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (ActionType action : ActionType.values()) {
            Gauge.builder("strata.autonomy.level.shift", trust, t -> t.get(action).levelShift())
                    .description("Earned-autonomy level shift; negative means promoted")
                    .tag("action", action.name())
                    .register(registry);
            Gauge.builder("strata.autonomy.success.streak", trust, t -> t.get(action).successStreak())
                    .description("Successes in a row towards the next promotion")
                    .tag("action", action.name())
                    .register(registry);
        }
    }
}
