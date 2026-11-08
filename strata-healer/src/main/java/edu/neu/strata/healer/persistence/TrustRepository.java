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

    private static final String COLUMNS = "action, successes, failures, updated_at";

    private final JdbcClient jdbc;

    public TrustRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * Adds one success or one failure. The increment happens in the database
     * ({@code successes = successes + 1}), so two writers never lose a count. The row is created
     * on an action's first run; {@code TrustService} serialises writers, and if a second healer
     * instance ever raced it on that first insert, the primary key makes one of them fail
     * (DuplicateKeyException, rolled back) rather than drop a count.
     */
    public void increment(ActionType action, boolean success, Instant at) {
        int added = success ? 1 : 0;
        OffsetDateTime time = OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
        if (update(action, added, time) == 1) {
            return;
        }
        jdbc.sql("INSERT INTO action_trust (" + COLUMNS + ") VALUES (?, ?, ?, ?)")
                .params(action.name(), added, 1 - added, time)
                .update();
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

    private int update(ActionType action, int added, OffsetDateTime time) {
        return jdbc.sql("UPDATE action_trust SET successes = successes + ?, failures = failures + ?, updated_at = ? "
                        + "WHERE action = ?")
                .params(added, 1 - added, time, action.name())
                .update();
    }

    private static TrustRecord map(ResultSet rs, int row) throws SQLException {
        return new TrustRecord(
                ActionType.valueOf(rs.getString("action")),
                rs.getInt("successes"),
                rs.getInt("failures"),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
