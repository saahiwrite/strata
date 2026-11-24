package edu.neu.strata.healer.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    private final ScaleOutAction scaleOut = mock(ScaleOutAction.class);
    private final RollbackAction rollback = mock(RollbackAction.class);
    private final DockerActionExecutor executor = new DockerActionExecutor(docker, scaleOut, rollback);

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
    void scaleOutIsHandedToTheScaleOutAction() {
        ExecutionResult expected = ExecutionResult.succeeded(ActionType.SCALE_OUT, "strata-order-service-1");
        when(scaleOut.run("strata-order-service-1")).thenReturn(expected);

        assertSame(expected, executor.execute(ActionType.SCALE_OUT, "strata-order-service-1"));
        verify(rollback, never()).run("strata-order-service-1");
    }

    @Test
    void rollbackIsHandedToTheRollbackAction() {
        ExecutionResult expected = ExecutionResult.succeeded(ActionType.ROLLBACK, "strata-order-service-1");
        when(rollback.run("strata-order-service-1")).thenReturn(expected);

        assertSame(expected, executor.execute(ActionType.ROLLBACK, "strata-order-service-1"));
        verify(scaleOut, never()).run("strata-order-service-1");
    }

    @Test
    void anExceptionFromAnActionBecomesAFailedResult() {
        when(rollback.run("strata-order-service-1")).thenThrow(new IllegalStateException("docker is down"));

        ExecutionResult result = executor.execute(ActionType.ROLLBACK, "strata-order-service-1");

        assertFalse(result.success());
        assertEquals("docker is down", result.detail());
    }

    @Test
    void undoOfAScaleOutIsHandedToTheScaleOutAction() {
        ExecutionResult executed = new ExecutionResult(ActionType.SCALE_OUT, "strata-order-service-1", true,
                "started strata-order-service-2", "strata-order-service-2");
        ExecutionResult removed = new ExecutionResult(ActionType.SCALE_OUT, "strata-order-service-1", true,
                "removed strata-order-service-2");
        when(scaleOut.undo(executed)).thenReturn(removed);

        assertSame(removed, executor.undo(executed));
    }

    @Test
    void aRestartOrRollbackCannotBeUndone() {
        ExecutionResult restarted = ExecutionResult.succeeded(ActionType.RESTART, "strata-order-service-1");
        ExecutionResult rolledBack = ExecutionResult.succeeded(ActionType.ROLLBACK, "strata-order-service-1");

        assertFalse(executor.undo(restarted).success());
        assertEquals("nothing to undo for ROLLBACK", executor.undo(rolledBack).detail());
        verify(scaleOut, never()).undo(restarted);
    }

    @Test
    void anExceptionWhileUndoingBecomesAFailedResult() {
        ExecutionResult executed = new ExecutionResult(ActionType.SCALE_OUT, "strata-order-service-1", true,
                "started strata-order-service-2", "strata-order-service-2");
        when(scaleOut.undo(executed)).thenThrow(new IllegalStateException("docker is down"));

        ExecutionResult result = executor.undo(executed);

        assertFalse(result.success());
        assertEquals("docker is down", result.detail());
    }
}
