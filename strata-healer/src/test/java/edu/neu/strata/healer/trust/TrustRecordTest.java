package edu.neu.strata.healer.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.neu.strata.healer.model.ActionType;
import edu.neu.strata.healer.policy.ActionHistory;
import org.junit.jupiter.api.Test;

class TrustRecordTest {

    @Test
    void emptyRecordHasNoRuns() {
        TrustRecord empty = TrustRecord.empty(ActionType.RESTART);
        assertThat(empty.runs()).isZero();
        assertThat(empty.successRate()).isZero();
        assertThat(empty.updatedAt()).isNull();
        assertThat(empty.toHistory()).isEqualTo(ActionHistory.NONE);
    }

    @Test
    void ratesAndHistory() {
        TrustRecord record = new TrustRecord(ActionType.RESTART, 3, 1, null);
        assertThat(record.runs()).isEqualTo(4);
        assertThat(record.successRate()).isEqualTo(0.75);
        assertThat(record.toHistory().failureRate()).isEqualTo(0.25);
    }

    @Test
    void rejectsBadValues() {
        assertThatThrownBy(() -> new TrustRecord(null, 0, 0, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TrustRecord(ActionType.RESTART, -1, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
