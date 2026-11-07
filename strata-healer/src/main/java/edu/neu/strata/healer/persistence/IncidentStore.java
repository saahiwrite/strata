package edu.neu.strata.healer.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import edu.neu.strata.healer.detection.Incident;
import edu.neu.strata.healer.detection.IncidentStatus;
import edu.neu.strata.healer.model.Alert;

// Write side of the incidents table. IncidentRepository only reads.
@Repository
public class IncidentStore {

    private final JdbcClient jdbc;

    public IncidentStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Incident insert(Alert alert, Instant openedAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO incidents (alert_name, service, container, severity, status, opened_at) "
                        + "VALUES (?, ?, ?, ?, 'OPEN', ?)")
                .params(alert.alertName(), alert.service(), alert.container(), alert.severity(), toOffset(openedAt))
                .update(keys, "id");
        return new Incident(keys.getKey().longValue(), alert, openedAt);
    }

    public Optional<Incident> findOpen(String service, String alertName) {
        return jdbc.sql("SELECT id, alert_name, service, container, severity, opened_at FROM incidents "
                        + "WHERE status = 'OPEN' AND service = ? AND alert_name = ? ORDER BY id LIMIT 1")
                .params(service, alertName)
                .query(IncidentStore::toIncident)
                .optional();
    }

    public void close(long id, IncidentStatus status, Instant closedAt) {
        jdbc.sql("UPDATE incidents SET status = ?, closed_at = ? WHERE id = ?")
                .params(status.name(), toOffset(closedAt), id)
                .update();
    }

    private static Incident toIncident(ResultSet rs, int row) throws SQLException {
        Instant openedAt = rs.getObject("opened_at", OffsetDateTime.class).toInstant();
        Alert alert = new Alert(rs.getString("alert_name"), rs.getString("service"),
                rs.getString("container"), rs.getString("severity"), openedAt);
        return new Incident(rs.getLong("id"), alert, openedAt);
    }

    private static OffsetDateTime toOffset(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
