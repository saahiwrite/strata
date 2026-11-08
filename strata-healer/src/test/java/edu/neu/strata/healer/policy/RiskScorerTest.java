package edu.neu.strata.healer.policy;

import static org.assertj.core.api.Assertions.assertThat;

import edu.neu.strata.healer.model.ActionType;
import edu.neu.strata.healer.model.AutonomyLevel;
import edu.neu.strata.healer.model.Diagnosis;
import edu.neu.strata.healer.model.RiskAssessment;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RiskScorerTest {

    /** The same numbers as the bundled policy.yaml, built by hand so these tests don't depend on it. */
    private static Policy policy(Map<ActionType, ActionPolicy> actions) {
        Map<RiskFactor, Integer> weights = new EnumMap<>(RiskFactor.class);
        weights.put(RiskFactor.BLAST_RADIUS, 25);
        weights.put(RiskFactor.REVERSIBILITY, 25);
        weights.put(RiskFactor.ENVIRONMENT, 15);
        weights.put(RiskFactor.DIAGNOSIS_CONFIDENCE, 20);
        weights.put(RiskFactor.PAST_FAILURES, 15);
        return new Policy("local", weights, new Thresholds(20, 40, 70),
                Map.of("local", 0.0, "staging", 0.5, "production", 1.0), actions);
    }

    private static final Policy POLICY = policy(Map.of(
            ActionType.RESTART, new ActionPolicy(0.2, 0.1, AutonomyLevel.L0_AUTO),
            ActionType.SCALE_OUT, new ActionPolicy(0.3, 0.2, AutonomyLevel.L0_AUTO),
            ActionType.ROLLBACK, new ActionPolicy(1.0, 0.8, AutonomyLevel.L2_APPROVAL)));

    private final RiskScorer scorer = new RiskScorer(POLICY);

    private static Diagnosis diagnosis(ActionType action, double confidence) {
        return new Diagnosis("rule", action, "order-service", confidence);
    }

    @Test
    void primaryUseCaseRestartRunsAutomatically() {
        RiskAssessment risk = scorer.assess(diagnosis(ActionType.RESTART, 0.95), ActionHistory.NONE);

        // 25*0.2 + 25*0.1 + 15*0 + 20*0.05 + 15*0 = 5 + 3 (2.5 rounds up) + 0 + 1 + 0
        assertThat(risk.factors()).containsOnly(
                Map.entry("blastRadius", 5),
                Map.entry("reversibility", 3),
                Map.entry("environment", 0),
                Map.entry("diagnosisConfidence", 1),
                Map.entry("pastFailures", 0));
        assertThat(risk.score()).isEqualTo(9);
        assertThat(risk.level()).isEqualTo(AutonomyLevel.L0_AUTO);
    }

    @Test
    void secondaryUseCaseRollbackNeedsApproval() {
        RiskAssessment risk = scorer.assess(diagnosis(ActionType.ROLLBACK, 0.9), ActionHistory.NONE);

        assertThat(risk.score()).isEqualTo(25 + 20 + 0 + 2 + 0);
        assertThat(risk.level()).isEqualTo(AutonomyLevel.L2_APPROVAL);
    }

    @Test
    void factorsAlwaysAddUpToTheScore() {
        RiskAssessment risk = scorer.assess(diagnosis(ActionType.SCALE_OUT, 0.37), new ActionHistory(3, 2), "staging");

        assertThat(risk.factors().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(risk.score());
        assertThat(risk.factors()).containsOnlyKeys(
                "blastRadius", "reversibility", "environment", "diagnosisConfidence", "pastFailures");
    }

    @Test
    void eachFactorRaisesTheScore() {
        Diagnosis sure = diagnosis(ActionType.RESTART, 1.0);
        int base = scorer.assess(sure, ActionHistory.NONE).score();

        assertThat(scorer.assess(sure, ActionHistory.NONE, "production").score()).isEqualTo(base + 15);
        assertThat(scorer.assess(diagnosis(ActionType.RESTART, 0.0), ActionHistory.NONE).score()).isEqualTo(base + 20);
        assertThat(scorer.assess(sure, new ActionHistory(0, 4)).score()).isEqualTo(base + 15);
        assertThat(scorer.assess(sure, new ActionHistory(1, 1)).factors().get("pastFailures")).isEqualTo(8);
    }

    @Test
    void pastFailuresCanPushAnActionUpALevel() {
        Diagnosis restart = diagnosis(ActionType.RESTART, 0.6);

        assertThat(scorer.assess(restart, new ActionHistory(10, 0)).level()).isEqualTo(AutonomyLevel.L0_AUTO);
        assertThat(scorer.assess(restart, new ActionHistory(0, 10)).level()).isEqualTo(AutonomyLevel.L1_AUTO_UNDO);
    }

    @Test
    void worstCaseIsHumanOnly() {
        RiskAssessment risk = scorer.assess(diagnosis(ActionType.ROLLBACK, 0.0), new ActionHistory(0, 3), "production");

        assertThat(risk.score()).isEqualTo(25 + 20 + 15 + 20 + 15);
        assertThat(risk.level()).isEqualTo(AutonomyLevel.L3_HUMAN_ONLY);
    }

    @Test
    void actionMissingFromThePolicyIsAlwaysHumanOnly() {
        RiskScorer onlyRestart = new RiskScorer(policy(Map.of(
                ActionType.RESTART, new ActionPolicy(0.2, 0.1, AutonomyLevel.L0_AUTO))));

        RiskAssessment risk = onlyRestart.assess(diagnosis(ActionType.SCALE_OUT, 1.0), ActionHistory.NONE);

        assertThat(risk.factors()).containsEntry("blastRadius", 25).containsEntry("reversibility", 25);
        assertThat(risk.level()).isEqualTo(AutonomyLevel.L3_HUMAN_ONLY);
    }

    @Test
    void unknownEnvironmentScoresAsRiskiest() {
        assertThat(scorer.assess(diagnosis(ActionType.RESTART, 1.0), null, "mystery").factors())
                .containsEntry("environment", 15);
    }

    @ParameterizedTest
    @CsvSource({
        "0, L0_AUTO", "19, L0_AUTO", "20, L1_AUTO_UNDO", "39, L1_AUTO_UNDO",
        "40, L2_APPROVAL", "69, L2_APPROVAL", "70, L3_HUMAN_ONLY", "100, L3_HUMAN_ONLY"
    })
    void thresholdsMapScoresToLevels(int score, AutonomyLevel level) {
        assertThat(POLICY.thresholds().levelFor(score)).isEqualTo(level);
    }

    @Test
    void bundledPolicyGivesTheDesignedLevels() {
        RiskScorer bundled = new RiskScorer(new PolicyLoader().loadDefault());

        assertThat(bundled.assess(diagnosis(ActionType.RESTART, 0.95), ActionHistory.NONE).level())
                .isEqualTo(AutonomyLevel.L0_AUTO);
        assertThat(bundled.assess(diagnosis(ActionType.ROLLBACK, 0.95), ActionHistory.NONE).level())
                .isEqualTo(AutonomyLevel.L2_APPROVAL);
    }
}
