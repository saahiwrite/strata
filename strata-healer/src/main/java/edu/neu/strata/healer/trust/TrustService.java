package edu.neu.strata.healer.trust;

import edu.neu.strata.healer.audit.AuditEventType;
import edu.neu.strata.healer.audit.AuditLogService;
import edu.neu.strata.healer.model.ActionType;
import edu.neu.strata.healer.persistence.TrustRepository;
import edu.neu.strata.healer.policy.ActionHistory;
import java.time.Clock;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps each action type's success record (FR10) and feeds it to the risk scorer.
 *
 * <p>After a verified run the orchestrator calls {@link #recordSuccess} or {@link #recordFailure};
 * the count changes in one transaction and a {@code TRUST_UPDATE} entry goes to the audit log
 * once it has committed. {@link #history} is what {@code RiskScorer} takes for its past-failures
 * factor. The counts are all-time for now; Phase 3's earned autonomy can add a window on top.
 */
public class TrustService {

    private final TrustRepository repository;
    private final TransactionTemplate transactions;
    private final AuditLogService audit;
    private final Clock clock;
    private final ReentrantLock writeLock = new ReentrantLock();

    public TrustService(TrustRepository repository, TransactionTemplate transactions, AuditLogService audit, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The service recovered after this action ran. */
    public TrustRecord recordSuccess(ActionType action, Long incidentId) {
        return record(action, true, incidentId);
    }

    /** The action failed, or the service didn't recover after it. */
    public TrustRecord recordFailure(ActionType action, Long incidentId) {
        return record(action, false, incidentId);
    }

    /** One action's record ({@link TrustRecord#empty} if it has never run). */
    public TrustRecord get(ActionType action) {
        Objects.requireNonNull(action, "action");
        return repository.find(action).orElseGet(() -> TrustRecord.empty(action));
    }

    /** The past-failures input for the risk scorer. */
    public ActionHistory history(ActionType action) {
        return get(action).toHistory();
    }

    /** Every action type's record, in {@link ActionType} order, including ones that never ran. */
    public List<TrustRecord> all() {
        Map<ActionType, TrustRecord> records = new EnumMap<>(ActionType.class);
        for (ActionType type : ActionType.values()) {
            records.put(type, TrustRecord.empty(type));
        }
        repository.findAll().forEach(r -> records.put(r.action(), r));
        return List.copyOf(records.values());
    }

    private TrustRecord record(ActionType action, boolean success, Long incidentId) {
        Objects.requireNonNull(action, "action");
        TrustRecord updated;
        writeLock.lock();
        try {
            updated = transactions.execute(status -> {
                repository.increment(action, success, clock.instant());
                return repository.find(action).orElseThrow();
            });
        } finally {
            writeLock.unlock();
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("action", action.name());
        details.put("outcome", success ? "SUCCESS" : "FAILURE");
        details.put("successes", updated.successes());
        details.put("failures", updated.failures());
        audit.append(AuditEventType.TRUST_UPDATE, incidentId, details);
        return updated;
    }
}
