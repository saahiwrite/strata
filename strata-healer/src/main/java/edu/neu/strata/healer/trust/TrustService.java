package edu.neu.strata.healer.trust;

import edu.neu.strata.healer.audit.AuditEventType;
import edu.neu.strata.healer.audit.AuditLogService;
import edu.neu.strata.healer.model.ActionType;
import edu.neu.strata.healer.persistence.TrustRepository;
import edu.neu.strata.healer.policy.ActionHistory;
import edu.neu.strata.healer.policy.AutonomyRules;
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
 * factor and its earned-autonomy shift.
 *
 * <p>Earned autonomy (FR10): every result also moves the action's standing by the policy's
 * {@link AutonomyRules}. When that promotes or demotes the action a level, an {@code AUTONOMY_CHANGE}
 * entry goes to the audit log as well, so every change in how freely STRATA acts is on record.
 */
public class TrustService {

    private final TrustRepository repository;
    private final TransactionTemplate transactions;
    private final AuditLogService audit;
    private final Clock clock;
    private final AutonomyRules autonomy;
    private final ReentrantLock writeLock = new ReentrantLock();

    /** A trust service with the default earned-autonomy rules. */
    public TrustService(TrustRepository repository, TransactionTemplate transactions, AuditLogService audit, Clock clock) {
        this(repository, transactions, audit, clock, AutonomyRules.DEFAULT);
    }

    public TrustService(TrustRepository repository, TransactionTemplate transactions, AuditLogService audit,
                        Clock clock, AutonomyRules autonomy) {
        this.autonomy = Objects.requireNonNull(autonomy, "autonomy");
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
        Change change;
        writeLock.lock();
        try {
            change = transactions.execute(status -> {
                TrustRecord current = repository.findForUpdate(action).orElseGet(() -> TrustRecord.empty(action));
                AutonomyRules.Standing standing = success
                        ? autonomy.afterSuccess(current.standing())
                        : autonomy.afterFailure(current.standing());
                TrustRecord updated = new TrustRecord(action,
                        current.successes() + (success ? 1 : 0),
                        current.failures() + (success ? 0 : 1),
                        standing.successStreak(), standing.levelShift(), clock.instant());
                repository.save(updated);
                return new Change(current, updated);
            });
        } finally {
            writeLock.unlock();
        }

        TrustRecord before = change.before();
        TrustRecord after = change.after();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("action", action.name());
        details.put("outcome", success ? "SUCCESS" : "FAILURE");
        details.put("successes", after.successes());
        details.put("failures", after.failures());
        details.put("successStreak", after.successStreak());
        details.put("levelShift", after.levelShift());
        audit.append(AuditEventType.TRUST_UPDATE, incidentId, details);

        if (after.levelShift() != before.levelShift()) {
            Map<String, Object> moved = new LinkedHashMap<>();
            moved.put("action", action.name());
            moved.put("change", after.levelShift() < before.levelShift() ? "PROMOTED" : "DEMOTED");
            moved.put("fromShift", before.levelShift());
            moved.put("toShift", after.levelShift());
            moved.put("reason", success
                    ? autonomy.promoteAfter() + " successes in a row"
                    : "the action failed or the service did not recover");
            audit.append(AuditEventType.AUTONOMY_CHANGE, incidentId, moved);
        }
        return after;
    }

    /** One action's record before and after a result. */
    private record Change(TrustRecord before, TrustRecord after) {
    }
}
