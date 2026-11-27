package edu.neu.strata.healer.persistence;

import edu.neu.strata.healer.model.ActionType;
import edu.neu.strata.healer.trust.TrustRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** SQL for the {@code action_trust} table: one row per action type that has run at least once. */
public class TrustRepository {

    private static final String COLUMNS = "action, successes, failures, success_streak, level_shift, updated_at";

    private final JdbcClient jdbc;

    public TrustRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * Writes one action's whole record, creating the row on its first run. Call inside the transaction
     * that read it with {@link #findForUpdate}, so the counts, streak and shift change together.
     * {@code TrustService} also serialises writers; if a second healer instance ever raced the first
     * insert, the primary key makes one of them fail and roll back rather than lose a count.
     */
    public void save(TrustRecord record) {
        OffsetDateTime time = OffsetDateTime.ofInstant(record.updatedAt(), ZoneOffset.UTC);
        int updated = jdbc.sql("UPDATE action_trust SET successes = ?, failures = ?, success_streak = ?, "
                        + "level_shift = ?, updated_at = ? WHERE action = ?")
                .params(record.successes(), record.failures(), record.successStreak(), record.levelShift(), time,
                        record.action().name())
                .update();
        if (updated == 0) {
            jdbc.sql("INSERT INTO action_trust (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?)")
                    .params(record.action().name(), record.successes(), record.failures(), record.successStreak(),
                            record.levelShift(), time)
                    .update();
        }
    }

    /** Reads one action's row and locks it until the transaction ends. */
    public Optional<TrustRecord> findForUpdate(ActionType action) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM action_trust WHERE action = ? FOR UPDATE")
                .param(action.name())
                .query(TrustRepository::map)
                .optional();
    }

    public Optional<TrustRecord> find(ActionType action) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM action_trust WHERE action = ?")
                .param(action.name())
                .query(TrustRepository::map)
                .optional();
    }

    public List<TrustRecord> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM action_trust ORDER BY action")
                .query(TrustRepository::map)
                .list();
    }

    private static TrustRecord map(ResultSet rs, int row) throws SQLException {
        return new TrustRecord(
                ActionType.valueOf(rs.getString("action")),
                rs.getInt("successes"),
                rs.getInt("failures"),
                rs.getInt("success_streak"),
                rs.getInt("level_shift"),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
