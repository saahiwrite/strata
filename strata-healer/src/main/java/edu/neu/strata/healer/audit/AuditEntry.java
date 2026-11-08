package edu.neu.strata.healer.audit;

import java.time.Instant;
import java.util.Objects;

/**
 * One entry of the hash-chained audit log, exactly as stored in {@code audit_log}.
 *
 * @param seq        position in the log, starting at 1 with no gaps
 * @param recordedAt when it was written (microsecond precision, like PostgreSQL)
 * @param eventType  what kind of decision it records
 * @param incidentId the incident it belongs to, or null for entries outside an incident
 * @param payload    the decision's details as canonical JSON (keys sorted)
 * @param prevHash   the previous entry's hash ({@link HashChain#GENESIS_HASH} for the first)
 * @param hash       SHA-256 over all the fields above (see {@link HashChain#hash})
 */
public record AuditEntry(
        long seq,
        Instant recordedAt,
        AuditEventType eventType,
        Long incidentId,
        String payload,
        String prevHash,
        String hash) {

    public AuditEntry {
        if (seq < 1) {
            throw new IllegalArgumentException("seq must be at least 1, was " + seq);
        }
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(prevHash, "prevHash");
        Objects.requireNonNull(hash, "hash");
    }
}
