package edu.neu.strata.healer.policy;

import static org.assertj.core.api.Assertions.assertThat;

import edu.neu.strata.healer.model.ActionType;
import edu.neu.strata.healer.model.AutonomyLevel;
import edu.neu.strata.healer.model.Diagnosis;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PolicyConfigTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(PolicyConfig.class);

    @Test
    void wiresTheBundledPolicyAndScorer() {
        context.run(ctx -> {
            assertThat(ctx.getBean(Policy.class).environment()).isEqualTo("local");
            Diagnosis crash = new Diagnosis("container-crash", ActionType.RESTART, "order-service", 0.95);
            assertThat(ctx.getBean(RiskScorer.class).assess(crash, ActionHistory.NONE).level())
                    .isEqualTo(AutonomyLevel.L0_AUTO);
        });
    }

    @Test
    void badPolicyLocationStopsStartup() {
        context.withPropertyValues("strata.policy.location=classpath:no-such-policy.yaml")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
