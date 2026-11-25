package edu.neu.strata.healer.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;

// Keeps the orchestration classes small: no class may depend on more than six collaborators
class OrchestrationDesignTest {

    private static final int MAX_COLLABORATORS = 6;

    private static final List<Class<?>> CLASSES = List.of(HealingOrchestrator.class, IncidentLifecycle.class,
            DiagnosisStage.class, AssessmentStage.class, ExecutionStage.class, ApprovalService.class);

    private static long collaborators(Constructor<?> constructor) {
        return Arrays.stream(constructor.getParameterTypes())
                .filter(type -> !type.isPrimitive() && type != Duration.class && type != Executor.class)
                .count();
    }

    @Test
    void noClassTakesMoreThanSixCollaborators() {
        for (Class<?> type : CLASSES) {
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                assertThat(collaborators(constructor))
                        .as(type.getSimpleName() + " constructor " + Arrays.toString(constructor.getParameterTypes()))
                        .isLessThanOrEqualTo(MAX_COLLABORATORS);
            }
        }
    }
}
