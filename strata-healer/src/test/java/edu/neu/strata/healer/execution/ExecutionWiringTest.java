package edu.neu.strata.healer.execution;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import com.github.dockerjava.api.DockerClient;

import edu.neu.strata.healer.verification.RecoveryVerifier;

// Checks Spring can build the executor with all its actions and their default settings
@SpringJUnitConfig
class ExecutionWiringTest {

    @Configuration
    @Import({DockerActionExecutor.class, ScaleOutAction.class, RollbackAction.class,
            ComposeServices.class, ContainerCloner.class})
    static class Config {

        @Bean
        DockerClient dockerClient() {
            return mock(DockerClient.class);
        }

        @Bean
        RecoveryVerifier recoveryVerifier() {
            return mock(RecoveryVerifier.class);
        }
    }

    @Autowired
    private ActionExecutor executor;

    @Test
    void executorIsBuiltWithItsActions() {
        assertInstanceOf(DockerActionExecutor.class, executor);
    }
}
