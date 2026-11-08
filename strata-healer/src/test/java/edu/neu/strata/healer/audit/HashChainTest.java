package edu.neu.strata.healer.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HashChainTest {

    private static final Instant T0 = Instant.parse("2026-11-05T10:15:30.123456789Z");

    private static List<AuditEntry> chain(int length) {
        List<AuditEntry> entries = new ArrayList<>();
        AuditEntry previous = null;
        for (int i = 0; i < length; i++) {
            previous = HashChain.next(previous, T0.plusSeconds(i), AuditEventType.DIAGNOSIS,
                    i % 2 == 0 ? 7L : null, "{\"n\":" + i + "}");
            entries.add(previous);
        }
        return entries;
    }

    private static AuditEntry withPayload(AuditEntry e, String payload) {
        return new AuditEntry(e.seq(), e.recordedAt(), e.eventType(), e.incidentId(), payload, e.prevHash(), e.hash());
    }

    @Test
    void firstEntryStartsFromTheGenesisHash() {
        AuditEntry first = HashChain.next(null, T0, AuditEventType.INCIDENT_OPENED, 1L, "{}");

        assertThat(first.seq()).isEqualTo(1);
        assertThat(first.prevHash()).isEqualTo(HashChain.GENESIS_HASH).hasSize(64);
        assertThat(first.hash()).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void eachEntryLinksToThePreviousOne() {
        List<AuditEntry> entries = chain(3);

        assertThat(entries.get(1).seq()).isEqualTo(2);
        assertThat(entries.get(1).prevHash()).isEqualTo(entries.get(0).hash());
        assertThat(entries.get(2).prevHash()).isEqualTo(entries.get(1).hash());
    }

    @Test
    void timesAreCutToMicroseconds() {
        assertThat(chain(1).get(0).recordedAt()).isEqualTo(Instant.parse("2026-11-05T10:15:30.123456Z"));
    }

    @Test
    void hashIsDeterministicAndKnown() {
        String hash = HashChain.hash(1, Instant.parse("2026-11-05T10:15:30Z"), AuditEventType.EXECUTION, 42L,
                "{\"action\":\"RESTART\"}", HashChain.GENESIS_HASH);

        assertThat(hash).isEqualTo(HashChain.hash(1, Instant.parse("2026-11-05T10:15:30Z"), AuditEventType.EXECUTION,
                42L, "{\"action\":\"RESTART\"}", HashChain.GENESIS_HASH));
        // Fixed value: if the hash input format ever changes, existing logs stop verifying, so this must fail too.
        assertThat(hash).isEqualTo(HashChain.sha256("1:1;20:2026-11-05T10:15:30Z;9:EXECUTION;2:42;20:{\"action\":\"RESTART\"};64:"
                + HashChain.GENESIS_HASH + ";"));
    }

    @Test
    void everyFieldChangesTheHash() {
        Instant at = Instant.parse("2026-11-05T10:15:30Z");
        String base = HashChain.hash(1, at, AuditEventType.EXECUTION, 42L, "{}", HashChain.GENESIS_HASH);

        assertThat(HashChain.hash(2, at, AuditEventType.EXECUTION, 42L, "{}", HashChain.GENESIS_HASH)).isNotEqualTo(base);
        assertThat(HashChain.hash(1, at.plusNanos(1000), AuditEventType.EXECUTION, 42L, "{}", HashChain.GENESIS_HASH)).isNotEqualTo(base);
        assertThat(HashChain.hash(1, at, AuditEventType.VERIFICATION, 42L, "{}", HashChain.GENESIS_HASH)).isNotEqualTo(base);
        assertThat(HashChain.hash(1, at, AuditEventType.EXECUTION, 43L, "{}", HashChain.GENESIS_HASH)).isNotEqualTo(base);
        assertThat(HashChain.hash(1, at, AuditEventType.EXECUTION, null, "{}", HashChain.GENESIS_HASH)).isNotEqualTo(base);
        assertThat(HashChain.hash(1, at, AuditEventType.EXECUTION, 42L, "{ }", HashChain.GENESIS_HASH)).isNotEqualTo(base);
        assertThat(HashChain.hash(1, at, AuditEventType.EXECUTION, 42L, "{}", "1".repeat(64))).isNotEqualTo(base);
    }

    @Test
    void fieldBoundariesCantBeShifted() {
        Instant at = Instant.parse("2026-11-05T10:15:30Z");
        // Same characters overall, split differently between payload and prevHash.
        assertThat(HashChain.hash(1, at, AuditEventType.EXECUTION, null, "ab", "c"))
                .isNotEqualTo(HashChain.hash(1, at, AuditEventType.EXECUTION, null, "a", "bc"));
    }

    @Test
    void anIntactChainVerifies() {
        List<AuditEntry> entries = chain(5);

        ChainVerification result = HashChain.verify(entries);

        assertThat(result.valid()).isTrue();
        assertThat(result.entries()).isEqualTo(5);
        assertThat(result.brokenAt()).isNull();
        assertThat(result.headHash()).isEqualTo(entries.get(4).hash());
    }

    @Test
    void anEmptyLogVerifies() {
        assertThat(HashChain.verify(List.of())).isEqualTo(ChainVerification.ok(0, HashChain.GENESIS_HASH));
    }

    @Test
    void detectsAChangedPayload() {
        List<AuditEntry> entries = chain(5);
        entries.set(2, withPayload(entries.get(2), "{\"n\":99}"));

        ChainVerification result = HashChain.verify(entries);

        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAt()).isEqualTo(3);
        assertThat(result.reason()).isEqualTo("contents do not match the stored hash");
        assertThat(result.headHash()).isEqualTo(entries.get(1).hash());
    }

    @Test
    void detectsARecomputedHashBecauseTheNextEntryNoLongerLinks() {
        List<AuditEntry> entries = chain(4);
        AuditEntry e = entries.get(1);
        String forged = HashChain.hash(e.seq(), e.recordedAt(), e.eventType(), e.incidentId(), "{\"n\":99}", e.prevHash());
        entries.set(1, new AuditEntry(e.seq(), e.recordedAt(), e.eventType(), e.incidentId(), "{\"n\":99}", e.prevHash(), forged));

        ChainVerification result = HashChain.verify(entries);

        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAt()).isEqualTo(3);
        assertThat(result.reason()).isEqualTo("previous hash does not match entry 2");
    }

    @Test
    void detectsARemovedEntry() {
        List<AuditEntry> entries = chain(4);
        entries.remove(1);

        ChainVerification result = HashChain.verify(entries);

        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAt()).isEqualTo(3);
        assertThat(result.reason()).isEqualTo("expected entry 2 but found 3");
    }

    @Test
    void detectsReorderedEntries() {
        List<AuditEntry> entries = chain(3);
        entries.add(0, entries.remove(1));

        assertThat(HashChain.verify(entries).brokenAt()).isEqualTo(2);
    }

    @Test
    void detectsAForgedFirstEntry() {
        List<AuditEntry> entries = chain(2);
        AuditEntry e = entries.get(0);
        entries.set(0, new AuditEntry(1, e.recordedAt(), e.eventType(), e.incidentId(), e.payload(), "f".repeat(64), e.hash()));

        ChainVerification result = HashChain.verify(entries);

        assertThat(result.brokenAt()).isEqualTo(1);
        assertThat(result.reason()).isEqualTo("first entry does not start from the genesis hash");
    }
}
