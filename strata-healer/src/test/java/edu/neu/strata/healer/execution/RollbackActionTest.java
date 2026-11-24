package edu.neu.strata.healer.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerConfig;

import edu.neu.strata.healer.model.ActionType;
import edu.neu.strata.healer.model.ExecutionResult;
import edu.neu.strata.healer.verification.RecoveryVerifier;

class RollbackActionTest {

    private static final String LABEL = "strata.previous-image";
    private static final String GOOD_IMAGE = "strata/order-service:1.0";

    private final DockerClient docker = mock(DockerClient.class);
    private final ComposeServices services = mock(ComposeServices.class);
    private final ContainerCloner cloner = mock(ContainerCloner.class);
    private final RecoveryVerifier verifier = mock(RecoveryVerifier.class);
    private final RollbackAction action = new RollbackAction(docker, services, cloner, verifier, LABEL, 60);

    private InspectContainerResponse instance(String id, String name, boolean withPreviousImage) {
        Map<String, String> labels = new HashMap<>();
        labels.put(ComposeServices.PROJECT_LABEL, "strata");
        labels.put(ComposeServices.SERVICE_LABEL, "order-service");
        if (withPreviousImage) {
            labels.put(LABEL, GOOD_IMAGE);
        }
        ContainerConfig config = mock(ContainerConfig.class);
        when(config.getLabels()).thenReturn(labels);
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        when(response.getConfig()).thenReturn(config);
        when(response.getId()).thenReturn(id);
        when(response.getName()).thenReturn("/" + name);

        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(inspect.exec()).thenReturn(response);
        when(docker.inspectContainerCmd(id)).thenReturn(inspect);
        when(docker.inspectContainerCmd(name)).thenReturn(inspect);

        StopContainerCmd stop = mock(StopContainerCmd.class, RETURNS_SELF);
        when(docker.stopContainerCmd(id)).thenReturn(stop);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(docker.removeContainerCmd(id)).thenReturn(remove);
        return response;
    }

    private Container running(String id) {
        Container c = mock(Container.class);
        when(c.getId()).thenReturn(id);
        return c;
    }

    @Test
    void replacesASingleInstanceWithThePreviousImage() {
        InspectContainerResponse only = instance("id1", "strata-order-service-1", true);
        List<Container> containers = List.of(running("id1"));
        when(services.running("strata", "order-service")).thenReturn(containers);

        ExecutionResult result = action.run("strata-order-service-1");

        assertTrue(result.success());
        InOrder order = inOrder(docker, cloner);
        order.verify(docker).stopContainerCmd("id1");
        order.verify(docker).removeContainerCmd("id1");
        ArgumentCaptor<Map<String, String>> labels = ArgumentCaptor.forClass(Map.class);
        order.verify(cloner).start(eq(only), eq("strata-order-service-1"), eq(GOOD_IMAGE),
                labels.capture(), eq("order-service"));
        assertFalse(labels.getValue().containsKey(LABEL));
        verify(verifier, never()).verify(anyString(), any());
    }

    @Test
    void failsWithoutTouchingAnythingWhenThereIsNoPreviousImage() {
        instance("id1", "strata-order-service-1", false);
        List<Container> containers = List.of(running("id1"));
        when(services.running("strata", "order-service")).thenReturn(containers);

        ExecutionResult result = action.run("strata-order-service-1");

        assertFalse(result.success());
        assertEquals(ActionType.ROLLBACK, result.action());
        verify(docker, never()).stopContainerCmd(anyString());
        verify(cloner, never()).start(any(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void rollsBackTheCanaryFirstAndThenTheRest() {
        InspectContainerResponse first = instance("id1", "strata-order-service-1", true);
        InspectContainerResponse second = instance("id2", "strata-order-service-2", true);
        List<Container> containers = List.of(running("id2"), running("id1"));
        when(services.running("strata", "order-service")).thenReturn(containers);
        when(verifier.verify("order-service", Duration.ofSeconds(60))).thenReturn(true);

        ExecutionResult result = action.run("strata-order-service-1");

        assertTrue(result.success());
        InOrder order = inOrder(cloner, verifier);
        order.verify(cloner).start(eq(first), eq("strata-order-service-1"), eq(GOOD_IMAGE), any(), anyString());
        order.verify(verifier).verify("order-service", Duration.ofSeconds(60));
        order.verify(cloner).start(eq(second), eq("strata-order-service-2"), eq(GOOD_IMAGE), any(), anyString());
    }

    @Test
    void leavesOtherInstancesAloneWhenTheCanaryDoesNotRecover() {
        instance("id1", "strata-order-service-1", true);
        instance("id2", "strata-order-service-2", true);
        List<Container> containers = List.of(running("id1"), running("id2"));
        when(services.running("strata", "order-service")).thenReturn(containers);
        when(verifier.verify("order-service", Duration.ofSeconds(60))).thenReturn(false);

        ExecutionResult result = action.run("strata-order-service-1");

        assertFalse(result.success());
        verify(docker, never()).stopContainerCmd("id2");
        verify(docker, never()).removeContainerCmd("id2");
    }

    @Test
    void refusesContainersThatAreNotPartOfACompose() {
        ContainerConfig config = mock(ContainerConfig.class);
        when(config.getLabels()).thenReturn(Map.of());
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        when(response.getConfig()).thenReturn(config);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(inspect.exec()).thenReturn(response);
        when(docker.inspectContainerCmd("loose")).thenReturn(inspect);

        assertFalse(action.run("loose").success());
    }
}
