package edu.neu.strata.healer.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerConfig;

import edu.neu.strata.healer.model.ActionType;
import edu.neu.strata.healer.model.ExecutionResult;

class ScaleOutActionTest {

    private static final String TARGET = "strata-order-service-1";

    private final DockerClient docker = mock(DockerClient.class);
    private final ComposeServices services = mock(ComposeServices.class);
    private final ContainerCloner cloner = mock(ContainerCloner.class);
    private final ScaleOutAction action = new ScaleOutAction(docker, services, cloner, 3);

    private InspectContainerResponse inspected(Map<String, String> labels) {
        ContainerConfig config = mock(ContainerConfig.class);
        when(config.getLabels()).thenReturn(labels);
        when(config.getImage()).thenReturn("strata-order-service");
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        when(response.getConfig()).thenReturn(config);
        InspectContainerCmd cmd = mock(InspectContainerCmd.class);
        when(cmd.exec()).thenReturn(response);
        when(docker.inspectContainerCmd(TARGET)).thenReturn(cmd);
        return response;
    }

    private Container running() {
        Container c = mock(Container.class);
        when(c.getState()).thenReturn("running");
        return c;
    }

    private Map<String, String> composeLabels() {
        return Map.of(ComposeServices.PROJECT_LABEL, "strata",
                ComposeServices.SERVICE_LABEL, "order-service",
                ComposeServices.NUMBER_LABEL, "1");
    }

    @Test
    void startsOneMoreInstanceOfTheSameService() {
        InspectContainerResponse source = inspected(composeLabels());
        Container one = running();
        when(services.all("strata", "order-service")).thenReturn(List.of(one));
        when(services.nextNumber(anyList())).thenReturn(2);

        ExecutionResult result = action.run(TARGET);

        assertTrue(result.success());
        assertEquals("started strata-order-service-2", result.detail());
        assertEquals("strata-order-service-2", result.created());
        ArgumentCaptor<Map<String, String>> labels = ArgumentCaptor.forClass(Map.class);
        verify(cloner).start(eq(source), eq("strata-order-service-2"), eq("strata-order-service"),
                labels.capture(), eq("order-service"));
        assertEquals("2", labels.getValue().get(ComposeServices.NUMBER_LABEL));
    }

    @Test
    void stopsAtTheMaximumNumberOfInstances() {
        inspected(composeLabels());
        List<Container> three = List.of(running(), running(), running());
        when(services.all("strata", "order-service")).thenReturn(three);

        ExecutionResult result = action.run(TARGET);

        assertFalse(result.success());
        assertEquals(ActionType.SCALE_OUT, result.action());
        verify(cloner, never()).start(any(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void stoppedContainersDoNotCountAgainstTheMaximum() {
        inspected(composeLabels());
        Container stopped = mock(Container.class);
        when(stopped.getState()).thenReturn("exited");
        List<Container> containers = List.of(running(), running(), stopped);
        when(services.all("strata", "order-service")).thenReturn(containers);
        when(services.nextNumber(anyList())).thenReturn(4);

        assertTrue(action.run(TARGET).success());
    }

    @Test
    void refusesContainersThatAreNotPartOfACompose() {
        inspected(Map.of());

        ExecutionResult result = action.run(TARGET);

        assertFalse(result.success());
        verify(cloner, never()).start(any(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void undoStopsAndRemovesTheInstanceThatWasStarted() {
        StopContainerCmd stop = mock(StopContainerCmd.class, RETURNS_SELF);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(docker.stopContainerCmd("strata-order-service-2")).thenReturn(stop);
        when(docker.removeContainerCmd("strata-order-service-2")).thenReturn(remove);
        ExecutionResult executed = new ExecutionResult(ActionType.SCALE_OUT, TARGET, true,
                "started strata-order-service-2", "strata-order-service-2");

        ExecutionResult undone = action.undo(executed);

        assertTrue(undone.success());
        assertEquals("removed strata-order-service-2", undone.detail());
        verify(stop).exec();
        verify(remove).exec();
    }

    @Test
    void undoWithoutAStartedInstanceDoesNothing() {
        ExecutionResult undone = action.undo(ExecutionResult.succeeded(ActionType.SCALE_OUT, TARGET));

        assertFalse(undone.success());
        verify(docker, never()).stopContainerCmd(anyString());
    }
}
