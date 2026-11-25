package edu.neu.strata.healer.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import edu.neu.strata.healer.audit.AuditLogService;
import edu.neu.strata.healer.model.ActionType;
import edu.neu.strata.healer.persistence.AuditLogRepository;
import edu.neu.strata.healer.persistence.TestDatabase;
import edu.neu.strata.healer.persistence.TrustRepository;
import edu.neu.strata.healer.trust.TrustService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class AutonomyGaugesTest {

    private final TestDatabase db = new TestDatabase();
    private final JdbcClient jdbc = JdbcClient.create(db.dataSource);
    private final AuditLogService audit =
            new AuditLogService(new AuditLogRepository(jdbc), db.transactions, Clock.systemUTC());
    private final TrustService trust =
            new TrustService(new TrustRepository(jdbc), db.transactions, audit, Clock.systemUTC());
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private double gauge(String name, ActionType action) {
        return registry.get(name).tag("action", action.name()).gauge().value();
    }

    @Test
    void gaugesFollowTheTrustRecordOnEveryRead() {
        new AutonomyGauges(trust).bindTo(registry);
        assertThat(gauge("strata.autonomy.level.shift", ActionType.RESTART)).isZero();

        for (int i = 0; i < 5; i++) {
            trust.recordSuccess(ActionType.RESTART, null);
        }
        assertThat(gauge("strata.autonomy.level.shift", ActionType.RESTART)).isEqualTo(-1);   // promoted
        trust.recordSuccess(ActionType.RESTART, null);
        assertThat(gauge("strata.autonomy.success.streak", ActionType.RESTART)).isEqualTo(1);

        trust.recordFailure(ActionType.ROLLBACK, null);
        assertThat(gauge("strata.autonomy.level.shift", ActionType.ROLLBACK)).isEqualTo(1);   // demoted
        assertThat(gauge("strata.autonomy.level.shift", ActionType.SCALE_OUT)).isZero();      // never ran
    }
}
