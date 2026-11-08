package edu.neu.strata.healer.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

class SchemaMigrationTest {

    private final JdbcClient jdbc = JdbcClient.create(new TestDatabase().dataSource);
    private final OffsetDateTime now = OffsetDateTime.parse("2026-11-05T10:00:00Z");

    @Test
    void createsEveryTable() {
        assertThat(jdbc.sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")
                .query(String.class).list())
                .contains("incidents", "decisions", "action_trust", "audit_log");
    }

    @Test
    void storesAnIncidentWithItsDecision() {
        jdbc.sql("INSERT INTO incidents (alert_name, service, container, severity, opened_at) "
                        + "VALUES ('ServiceDown', 'order-service', 'strata-order-service-1', 'critical', ?)")
                .param(now).update();
        long incident = jdbc.sql("SELECT id FROM incidents WHERE alert_name = 'ServiceDown'").query(Long.class).single();
        jdbc.sql("INSERT INTO decisions (incident_id, rule_id, action, target, confidence, allowed, risk_score, "
                        + "risk_factors, autonomy_level, decided_at) VALUES (?, 'container-crash', 'RESTART', "
                        + "'strata-order-service-1', 0.95, TRUE, 9, '{}', 'L0_AUTO', ?)")
                .params(incident, now).update();

        assertThat(jdbc.sql("SELECT status FROM incidents WHERE id = ?").param(incident).query(String.class).single())
                .isEqualTo("OPEN");
        assertThat(jdbc.sql("SELECT outcome FROM decisions WHERE incident_id = ?").param(incident)
                .query(String.class).single()).isEqualTo("PENDING");
    }

    @Test
    void rejectsBadValues() {
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO incidents (alert_name, service, status, opened_at) "
                + "VALUES ('ServiceDown', 'order-service', 'MAYBE', ?)").param(now).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO decisions (incident_id, rule_id, action, target, confidence, "
                + "allowed, decided_at) VALUES (999, 'r', 'RESTART', 't', 0.5, TRUE, ?)").param(now).update())
                .as("decision for a missing incident").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO action_trust (action, successes, failures, updated_at) "
                + "VALUES ('RESTART', -1, 0, ?)").param(now).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
