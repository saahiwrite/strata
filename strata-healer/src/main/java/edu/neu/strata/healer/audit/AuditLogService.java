package edu.neu.strata.healer.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import edu.neu.strata.healer.persistence.AuditLogRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes decisions to the hash-chained audit log and checks the chain (FR11).
 *
 * <p>Appends are serialised: the lock is held until the insert has committed, so two decisions
 * written at once can never chain onto the same entry. If a second healer instance ever writes
 * to the same database, the primary key on {@code seq} and the unique {@code prev_hash} make
 * the losing insert fail instead of forking the chain.
 */
public class AuditLogService {

    /** Largest page {@link #latest} returns. */
    public static final int MAX_PAGE = 500;

    private final AuditLogRepository repository;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ObjectMapper json;
    private final ReentrantLock appendLock = new ReentrantLock();

    public AuditLogService(AuditLogRepository repository, TransactionTemplate transactions, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.json = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    /**
     * Appends one decision.
     *
     * @param type       what kind of decision it is
     * @param incidentId the incident it belongs to, or null
     * @param details    the decision's details (records, maps, numbers, text...); written as JSON
     *                   with sorted keys, so the same details always give the same payload
     * @return the stored entry, with its seq and hash
     */
    public AuditEntry append(AuditEventType type, Long incidentId, Object details) {
        Objects.requireNonNull(type, "type");
        String payload = toJson(details == null ? Map.of() : details);
        appendLock.lock();
        try {
            return transactions.execute(status -> {
                AuditEntry entry = HashChain.next(repository.findLast().orElse(null), clock.instant(),
                        type, incidentId, payload);
                repository.insert(entry);
                return entry;
            });
        } finally {
            appendLock.unlock();
        }
    }

    /** Re-checks every entry of the log. */
    public ChainVerification verify() {
        return HashChain.verify(transactions.execute(status -> repository.findAll()));
    }

    /** The newest entries, newest first ({@code limit} is clamped to 1-{@value #MAX_PAGE}). */
    public List<AuditEntry> latest(int limit) {
        return repository.findLatest(Math.max(1, Math.min(limit, MAX_PAGE)));
    }

    /** One incident's entries, oldest first. */
    public List<AuditEntry> forIncident(long incidentId) {
        return repository.findByIncident(incidentId);
    }

    private String toJson(Object details) {
        try {
            return json.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("audit details can't be written as JSON: " + e.getMessage(), e);
        }
    }
}
