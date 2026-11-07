package edu.neu.strata.healer.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;

/**
 * The SHA-256 hash chain behind the tamper-evident audit log (FR11). Pure functions, no I/O.
 *
 * <p>Each entry's hash covers its seq, time, event type, incident id, payload and the previous
 * entry's hash. Every field is written as {@code <length>:<value>;} before hashing, so no two
 * different entries can produce the same input (for example by moving text from the payload
 * into the event type). Changing any stored field, reordering entries or removing one from the
 * middle makes {@link #verify} report the first entry that no longer fits.
 */
public final class HashChain {

    /** The "previous hash" of the first entry: 64 zeros. */
    public static final String GENESIS_HASH = "0".repeat(64);

    private HashChain() {
    }

    /** The hash an entry with these fields must have. */
    public static String hash(long seq, Instant recordedAt, AuditEventType eventType, Long incidentId,
                              String payload, String prevHash) {
        StringBuilder input = new StringBuilder();
        field(input, Long.toString(seq));
        field(input, recordedAt.toString());
        field(input, eventType.name());
        field(input, incidentId == null ? "" : incidentId.toString());
        field(input, payload);
        field(input, prevHash);
        return sha256(input.toString());
    }

    /**
     * Builds the entry that follows {@code previous} (null for the first entry). The time is cut
     * to microseconds, the precision PostgreSQL stores, so the hash still matches after a round trip.
     */
    public static AuditEntry next(AuditEntry previous, Instant recordedAt, AuditEventType eventType,
                                  Long incidentId, String payload) {
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(payload, "payload");
        long seq = previous == null ? 1 : previous.seq() + 1;
        String prevHash = previous == null ? GENESIS_HASH : previous.hash();
        Instant at = recordedAt.truncatedTo(ChronoUnit.MICROS);
        return new AuditEntry(seq, at, eventType, incidentId, payload, prevHash,
                hash(seq, at, eventType, incidentId, payload, prevHash));
    }

    /** Checks a whole log, given in seq order. */
    public static ChainVerification verify(Iterable<AuditEntry> entries) {
        long expectedSeq = 1;
        String prevHash = GENESIS_HASH;
        long checked = 0;
        for (AuditEntry entry : entries) {
            checked++;
            if (entry.seq() != expectedSeq) {
                return ChainVerification.broken(checked, entry.seq(),
                        "expected entry " + expectedSeq + " but found " + entry.seq(), prevHash);
            }
            if (!entry.prevHash().equals(prevHash)) {
                return ChainVerification.broken(checked, entry.seq(), entry.seq() == 1
                        ? "first entry does not start from the genesis hash"
                        : "previous hash does not match entry " + (entry.seq() - 1), prevHash);
            }
            String expected = hash(entry.seq(), entry.recordedAt(), entry.eventType(), entry.incidentId(),
                    entry.payload(), entry.prevHash());
            if (!expected.equals(entry.hash())) {
                return ChainVerification.broken(checked, entry.seq(),
                        "contents do not match the stored hash", prevHash);
            }
            prevHash = entry.hash();
            expectedSeq++;
        }
        return ChainVerification.ok(checked, prevHash);
    }

    private static void field(StringBuilder input, String value) {
        input.append(value.length()).append(':').append(value).append(';');
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", e);
        }
    }
}
