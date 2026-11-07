package edu.neu.strata.healer.persistence;

import edu.neu.strata.healer.audit.AuditEntry;
import edu.neu.strata.healer.audit.AuditEventType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * SQL for the {@code audit_log} table. It only ever inserts and reads: the log is append-only,
 * so there is deliberately no update or delete.
 */
public class AuditLogRepository {

    private static final String COLUMNS = "seq, recorded_at, event_type, incident_id, payload, prev_hash, hash";

    private final JdbcClient jdbc;

    public AuditLogRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** The newest entry, if any. */
    public Optional<AuditEntry> findLast() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM audit_log ORDER BY seq DESC LIMIT 1")
                .query(AuditLogRepository::map)
                .optional();
    }

    /** Fails (primary-key or unique violation) if another writer already took this seq or chained onto the same entry. */
    public void insert(AuditEntry entry) {
        jdbc.sql("INSERT INTO audit_log (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(entry.seq(),
                        OffsetDateTime.ofInstant(entry.recordedAt(), ZoneOffset.UTC),
                        entry.eventType().name(),
                        entry.incidentId(),
                        entry.payload(),
                        entry.prevHash(),
                        entry.hash())
                .update();
    }

    /** Every entry, oldest first. */
    public List<AuditEntry> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM audit_log ORDER BY seq")
                .query(AuditLogRepository::map)
                .list();
    }

    /** The newest {@code limit} entries, newest first. */
    public List<AuditEntry> findLatest(int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM audit_log ORDER BY seq DESC LIMIT ?")
                .param(limit)
                .query(AuditLogRepository::map)
                .list();
    }

    /** An incident's entries, oldest first. */
    public List<AuditEntry> findByIncident(long incidentId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM audit_log WHERE incident_id = ? ORDER BY seq")
                .param(incidentId)
                .query(AuditLogRepository::map)
                .list();
    }

    private static AuditEntry map(ResultSet rs, int row) throws SQLException {
        long incident = rs.getLong("incident_id");
        Long incidentId = rs.wasNull() ? null : incident;
        return new AuditEntry(
                rs.getLong("seq"),
                rs.getObject("recorded_at", OffsetDateTime.class).toInstant(),
                AuditEventType.valueOf(rs.getString("event_type")),
                incidentId,
                rs.getString("payload"),
                rs.getString("prev_hash"),
                rs.getString("hash"));
    }
}
