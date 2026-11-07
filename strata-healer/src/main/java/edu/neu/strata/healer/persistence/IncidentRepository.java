package edu.neu.strata.healer.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reads incidents and their decisions for the incidents API. The healing pipeline writes these
 * tables; this class only reads them.
 */
public class IncidentRepository {

    private static final String INCIDENT_COLUMNS =
            "id, alert_name, service, container, severity, status, opened_at, closed_at";
    private static final String DECISION_COLUMNS = "id, incident_id, rule_id, action, target, confidence, allowed, "
            + "rejection_reason, risk_score, risk_factors, autonomy_level, outcome, decided_at";

    private final JdbcClient jdbc;

    public IncidentRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** The newest {@code limit} incidents, newest first, optionally only those with one status. */
    public List<IncidentRecord> findLatest(String status, int limit) {
        if (status == null) {
            return jdbc.sql("SELECT " + INCIDENT_COLUMNS + " FROM incidents ORDER BY opened_at DESC, id DESC LIMIT ?")
                    .param(limit)
                    .query(IncidentRepository::mapIncident)
                    .list();
        }
        return jdbc.sql("SELECT " + INCIDENT_COLUMNS + " FROM incidents WHERE status = ? "
                        + "ORDER BY opened_at DESC, id DESC LIMIT ?")
                .params(status, limit)
                .query(IncidentRepository::mapIncident)
                .list();
    }

    public Optional<IncidentRecord> findById(long id) {
        return jdbc.sql("SELECT " + INCIDENT_COLUMNS + " FROM incidents WHERE id = ?")
                .param(id)
                .query(IncidentRepository::mapIncident)
                .optional();
    }

    /** An incident's decisions, oldest first. */
    public List<DecisionRecord> findDecisions(long incidentId) {
        return jdbc.sql("SELECT " + DECISION_COLUMNS + " FROM decisions WHERE incident_id = ? ORDER BY decided_at, id")
                .param(incidentId)
                .query(IncidentRepository::mapDecision)
                .list();
    }

    private static IncidentRecord mapIncident(ResultSet rs, int row) throws SQLException {
        return new IncidentRecord(
                rs.getLong("id"),
                rs.getString("alert_name"),
                rs.getString("service"),
                rs.getString("container"),
                rs.getString("severity"),
                rs.getString("status"),
                instant(rs, "opened_at"),
                instant(rs, "closed_at"));
    }

    private static DecisionRecord mapDecision(ResultSet rs, int row) throws SQLException {
        int score = rs.getInt("risk_score");
        Integer riskScore = rs.wasNull() ? null : score;
        return new DecisionRecord(
                rs.getLong("id"),
                rs.getLong("incident_id"),
                rs.getString("rule_id"),
                rs.getString("action"),
                rs.getString("target"),
                rs.getDouble("confidence"),
                rs.getBoolean("allowed"),
                rs.getString("rejection_reason"),
                riskScore,
                rs.getString("risk_factors"),
                rs.getString("autonomy_level"),
                rs.getString("outcome"),
                instant(rs, "decided_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
