package edu.neu.strata.healer.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.RestartContainerCmd;

import edu.neu.strata.healer.model.ActionType;
import edu.neu.strata.healer.model.ExecutionResult;

class DockerActionExecutorTest {

    private final DockerClient docker = mock(DockerClient.class);
    private final RestartContainerCmd restartCmd = mock(RestartContainerCmd.class);
    private final DockerActionExecutor executor = new DockerActionExecutor(docker);

    @Test
    void restartCallsDockerForTheContainer() {
        when(docker.restartContainerCmd("strata-order-service-1")).thenReturn(restartCmd);

        ExecutionResult result = executor.execute(ActionType.RESTART, "strata-order-service-1");

        assertTrue(result.success());
        verify(restartCmd).exec();
    }

    @Test
    void failedRestartIsReportedNotThrown() {
        when(docker.restartContainerCmd("missing")).thenReturn(restartCmd);
        when(restartCmd.exec()).thenThrow(new RuntimeException("no such container"));

        ExecutionResult result = executor.execute(ActionType.RESTART, "missing");

        assertFalse(result.success());
        assertEquals("no such container", result.detail());
    }

    @Test
    void otherActionsAreNotSupportedYet() {
        ExecutionResult result = executor.execute(ActionType.ROLLBACK, "strata-order-service-1");

        assertFalse(result.success());
        verify(docker, never()).restartContainerCmd("strata-order-service-1");
    }
}
