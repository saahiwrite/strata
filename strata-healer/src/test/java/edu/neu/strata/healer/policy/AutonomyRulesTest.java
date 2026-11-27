package edu.neu.strata.healer.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import edu.neu.strata.healer.model.AutonomyLevel;
import edu.neu.strata.healer.policy.AutonomyRules.Standing;

class AutonomyRulesTest {

    private final AutonomyRules rules = new AutonomyRules(3, 1, 1, 2);

    @Test
    void aFullRunOfSuccessesPromotesOneLevelAndStartsANewRun() {
        Standing s = Standing.START;
        s = rules.afterSuccess(s);
        s = rules.afterSuccess(s);
        assertThat(s).isEqualTo(new Standing(2, 0));

        assertThat(rules.afterSuccess(s)).isEqualTo(new Standing(0, -1));
    }

    @Test
    void promotionStopsAtMaxPromotionButTheRunKeepsCounting() {
        Standing promoted = new Standing(2, -1);

        assertThat(rules.afterSuccess(promoted)).isEqualTo(new Standing(3, -1));
    }

    @Test
    void aFailureDemotesAndResetsTheRun() {
        assertThat(rules.afterFailure(new Standing(2, -1))).isEqualTo(new Standing(0, 0));
        assertThat(rules.afterFailure(new Standing(0, 0))).isEqualTo(new Standing(0, 1));
        assertThat(rules.afterFailure(new Standing(0, 2))).isEqualTo(new Standing(0, 2));
    }

    @Test
    void zeroTurnsPromotionOrDemotionOff() {
        AutonomyRules frozen = new AutonomyRules(1, 0, 0, 0);

        assertThat(frozen.afterSuccess(Standing.START).levelShift()).isZero();
        assertThat(frozen.afterFailure(Standing.START).levelShift()).isZero();
    }

    @ParameterizedTest
    @CsvSource({
            "L0_AUTO,       -1, L0_AUTO",
            "L1_AUTO_UNDO,  -1, L0_AUTO",
            "L2_APPROVAL,   -1, L1_AUTO_UNDO",
            "L3_HUMAN_ONLY, -1, L3_HUMAN_ONLY",
            "L3_HUMAN_ONLY, -2, L3_HUMAN_ONLY",
            "L0_AUTO,        1, L1_AUTO_UNDO",
            "L0_AUTO,        2, L2_APPROVAL",
            "L2_APPROVAL,    2, L3_HUMAN_ONLY",
            "L1_AUTO_UNDO,   0, L1_AUTO_UNDO"
    })
    void shiftMovesTheLevelButNeverPromotesOutOfL3(AutonomyLevel scored, int shift, AutonomyLevel expected) {
        assertThat(rules.apply(scored, shift)).isEqualTo(expected);
    }

    @Test
    void rejectsLimitsThatMakeNoSense() {
        assertThatThrownBy(() -> new AutonomyRules(0, 1, 1, 2)).hasMessageContaining("promoteAfter");
        assertThatThrownBy(() -> new AutonomyRules(5, -1, 1, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AutonomyRules(5, 1, 1, 4)).isInstanceOf(IllegalArgumentException.class);
    }
}
