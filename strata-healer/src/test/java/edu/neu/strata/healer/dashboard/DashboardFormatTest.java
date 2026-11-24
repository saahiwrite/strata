package edu.neu.strata.healer.dashboard;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardFormatTest {

    private final DashboardFormat fmt = DashboardFormat.INSTANCE;

    @Test
    void formatsLevels() {
        assertThat(fmt.level("L0_AUTO")).isEqualTo("L0 auto");
        assertThat(fmt.level("L1_AUTO_UNDO")).isEqualTo("L1 auto undo");
        assertThat(fmt.level("L2_APPROVAL")).isEqualTo("L2 approval");
        assertThat(fmt.level(null)).isEqualTo("—");
    }

    @Test
    void formatsDurationsAndTimes() {
        Instant t = Instant.parse("2026-11-20T10:00:00Z");
        assertThat(fmt.duration(t, t.plusSeconds(15))).isEqualTo("15s");
        assertThat(fmt.duration(t, t.plusSeconds(75))).isEqualTo("1m 15s");
        assertThat(fmt.duration(t, null)).isEqualTo("—");
        assertThat(fmt.time(t)).isEqualTo("2026-11-20 10:00:00 UTC");
    }

    @Test
    void readsRiskFactorsLargestFirst() {
        assertThat(RiskFactors.parse("{\"a\":2,\"b\":25,\"c\":5}")).containsExactly(
                java.util.Map.entry("b", 25), java.util.Map.entry("c", 5), java.util.Map.entry("a", 2));
        assertThat(RiskFactors.parse(null)).isEmpty();
        assertThat(RiskFactors.parse("not json")).isEmpty();
    }
}
