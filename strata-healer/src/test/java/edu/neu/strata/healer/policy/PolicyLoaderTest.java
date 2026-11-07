package edu.neu.strata.healer.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.neu.strata.healer.model.ActionType;
import edu.neu.strata.healer.model.AutonomyLevel;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PolicyLoaderTest {

    private static final String VALID = """
            environment: local
            weights:
              blastRadius: 25
              reversibility: 25
              environment: 15
              diagnosisConfidence: 20
              pastFailures: 15
            thresholds:
              l1From: 20
              l2From: 40
              l3From: 70
            environments:
              local: 0.0
              production: 1
            actions:
              RESTART:
                blastRadius: 0.2
                reversibility: 0.1
              ROLLBACK:
                blastRadius: 1.0
                reversibility: 0.8
                minLevel: L2_APPROVAL
            """;

    private final PolicyLoader loader = new PolicyLoader();

    private Policy load(String yaml) {
        return loader.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "test.yaml");
    }

    private void assertRejected(String yaml, String messagePart) {
        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(PolicyException.class)
                .hasMessageStartingWith("test.yaml")
                .hasMessageContaining(messagePart);
    }

    @Test
    void readsEverySection() {
        Policy policy = load(VALID);

        assertThat(policy.environment()).isEqualTo("local");
        assertThat(policy.weight(RiskFactor.BLAST_RADIUS)).isEqualTo(25);
        assertThat(policy.weight(RiskFactor.PAST_FAILURES)).isEqualTo(15);
        assertThat(policy.thresholds()).isEqualTo(new Thresholds(20, 40, 70));
        assertThat(policy.environmentRisk("production")).isEqualTo(1.0);
        assertThat(policy.action(ActionType.RESTART)).isEqualTo(new ActionPolicy(0.2, 0.1, AutonomyLevel.L0_AUTO));
        assertThat(policy.action(ActionType.ROLLBACK).minLevel()).isEqualTo(AutonomyLevel.L2_APPROVAL);
        assertThat(policy.action(ActionType.SCALE_OUT)).isNull();
    }

    @Test
    void unknownEnvironmentCountsAsRiskiest() {
        Policy policy = load(VALID);

        assertThat(policy.environmentRisk("moon-base")).isEqualTo(Policy.UNKNOWN_ENVIRONMENT_RISK);
        assertThat(policy.environmentRisk(null)).isEqualTo(Policy.UNKNOWN_ENVIRONMENT_RISK);
    }

    @Test
    void bundledPolicyLoadsAndListsEveryAction() {
        Policy policy = loader.loadDefault();

        assertThat(policy.actions()).containsOnlyKeys(ActionType.values());
        assertThat(policy.action(ActionType.ROLLBACK).minLevel()).isEqualTo(AutonomyLevel.L2_APPROVAL);
        assertThat(policy.environments()).containsKey(policy.environment());
    }

    @Test
    void loadsFromAFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("policy.yaml");
        Files.writeString(file, VALID);

        assertThat(loader.load(file).environment()).isEqualTo("local");
        assertThatThrownBy(() -> loader.load(dir.resolve("missing.yaml"))).isInstanceOf(PolicyException.class);
    }

    @Test
    void missingResourceFails() {
        assertThatThrownBy(() -> loader.loadResource("no-such-policy.yaml"))
                .isInstanceOf(PolicyException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void rejectsWeightsThatDontAddUpTo100() {
        assertRejected(VALID.replace("pastFailures: 15", "pastFailures: 16"), "add up to 100, were 101");
    }

    @Test
    void rejectsMissingOrNegativeWeights() {
        assertRejected(VALID.replace("  pastFailures: 15\n", "").replace("blastRadius: 25", "blastRadius: 40"),
                "missing weight for pastFailures");
        assertRejected(VALID.replace("pastFailures: 15", "pastFailures: -5").replace("blastRadius: 25", "blastRadius: 45"),
                "must not be negative");
    }

    @Test
    void rejectsUnknownNames() {
        assertRejected(VALID.replace("pastFailures:", "pastFailure:"), "unknown weight 'pastFailure'");
        assertRejected(VALID.replace("RESTART:", "DELETE_DATABASE:"), "unknown ActionType 'DELETE_DATABASE'");
        assertRejected(VALID.replace("L2_APPROVAL", "L9"), "unknown AutonomyLevel 'L9'");
        assertRejected(VALID + "debug: true\n", "unknown key 'debug'");
        assertRejected(VALID.replace("    reversibility: 0.1", "    reversibility: 0.1\n    speed: 3"), "unknown key 'speed'");
    }

    @Test
    void rejectsValuesOutsideZeroToOne() {
        assertRejected(VALID.replace("blastRadius: 0.2", "blastRadius: 1.5"), "blastRadius must be between 0.0 and 1.0");
        assertRejected(VALID.replace("local: 0.0", "local: -0.1"), "environments.local");
    }

    @Test
    void rejectsThresholdsOutOfOrder() {
        assertRejected(VALID.replace("l2From: 40", "l2From: 80"), "l1From < l2From < l3From");
        assertRejected(VALID.replace("l3From: 70", "l3From: 101"), "<= 100");
    }

    @Test
    void rejectsWrongTypesAndMissingSections() {
        assertRejected(VALID.replace("blastRadius: 25", "blastRadius: lots"), "weights.blastRadius must be a whole number");
        assertRejected(VALID.replace("l1From: 20", "l1From: 20.5"), "thresholds.l1From must be a whole number");
        assertRejected(VALID.replace("environment: local\n", ""), "environment must be non-blank text");
        assertRejected("environment: local\n", "missing 'weights'");
        assertRejected("- just\n- a list\n", "must be a mapping");
    }

    @Test
    void rejectsBrokenYamlAndDuplicateKeys() {
        assertRejected("weights: [unclosed", "not valid YAML");
        assertRejected(VALID.replace("  l1From: 20", "  l1From: 20\n  l1From: 25"), "not valid YAML");
    }

    @Test
    void refusesToBuildJavaObjectsFromTags() {
        assertRejected("!!java.io.File [\"/etc/passwd\"]", "not valid YAML");
    }

    @Test
    void readsTheAllowList() {
        Policy policy = load(VALID + """
                allowList:
                  actions: [RESTART, SCALE_OUT]
                  targets: [order-service, strata-order-service-*]
                """);

        assertThat(policy.allowList().actions()).containsExactlyInAnyOrder(ActionType.RESTART, ActionType.SCALE_OUT);
        assertThat(policy.allowList().targets()).containsExactly("order-service", "strata-order-service-*");
    }

    @Test
    void noAllowListAllowsNothing() {
        assertThat(load(VALID).allowList()).isEqualTo(AllowList.NONE);
    }

    @Test
    void bundledAllowListOnlyRestartsTheTargetServices() {
        AllowList allowList = loader.loadDefault().allowList();

        assertThat(allowList.actions()).containsExactly(ActionType.RESTART);
        assertThat(allowList.allowsTarget("order-service")).isTrue();
        assertThat(allowList.allowsTarget("strata-inventory-service-1")).isTrue();
        assertThat(allowList.allowsTarget("postgres")).isFalse();
    }

    @Test
    void rejectsBadAllowLists() {
        assertRejected(VALID + "allowList:\n  actions: [DELETE_DATABASE]\n", "unknown ActionType 'DELETE_DATABASE'");
        assertRejected(VALID + "allowList:\n  commands: [ls]\n", "unknown key 'commands' in allowList");
        assertRejected(VALID + "allowList:\n  actions: RESTART\n", "allowList.actions must be a list");
        assertRejected(VALID + "allowList:\n  targets: ['order-service; rm -rf /']\n", "must be a container or service name");
        assertRejected(VALID + "allowList:\n  targets: ['*']\n", "must be a container or service name");
        assertRejected(VALID + "allowList: [RESTART]\n", "allowList must be a mapping");
    }
}
