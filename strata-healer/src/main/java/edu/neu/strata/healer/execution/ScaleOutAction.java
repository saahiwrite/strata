package edu.neu.strata.healer.execution;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;

import edu.neu.strata.healer.model.ActionType;
import edu.neu.strata.healer.model.ExecutionResult;

// Starts one more instance of the service the target container belongs to
@Component
public class ScaleOutAction {

    private final DockerClient docker;
    private final ComposeServices services;
    private final ContainerCloner cloner;
    private final int maxInstances;

    public ScaleOutAction(DockerClient docker, ComposeServices services, ContainerCloner cloner,
                          @Value("${strata.scale.max-instances:3}") int maxInstances) {
        this.docker = docker;
        this.services = services;
        this.cloner = cloner;
        this.maxInstances = maxInstances;
    }

    public ExecutionResult run(String target) {
        InspectContainerResponse source = docker.inspectContainerCmd(target).exec();
        Map<String, String> labels = source.getConfig().getLabels();
        String service = labels == null ? null : labels.get(ComposeServices.SERVICE_LABEL);
        String project = labels == null ? null : labels.get(ComposeServices.PROJECT_LABEL);
        if (service == null || project == null) {
            return ExecutionResult.failed(ActionType.SCALE_OUT, target, target + " is not a Compose container");
        }

        List<Container> existing = services.all(project, service);
        long running = existing.stream().filter(c -> "running".equals(c.getState())).count();
        if (running >= maxInstances) {
            return ExecutionResult.failed(ActionType.SCALE_OUT, target,
                    service + " already has " + running + " instances (max " + maxInstances + ")");
        }

        int number = services.nextNumber(existing);
        String name = project + "-" + service + "-" + number;
        Map<String, String> newLabels = new HashMap<>(labels);
        newLabels.put(ComposeServices.NUMBER_LABEL, String.valueOf(number));

        cloner.start(source, name, source.getConfig().getImage(), newLabels, service);
        return new ExecutionResult(ActionType.SCALE_OUT, target, true, "started " + name, name);
    }

    // Stops and removes the instance this action started
    public ExecutionResult undo(ExecutionResult executed) {
        String name = executed.created();
        if (name == null) {
            return ExecutionResult.failed(ActionType.SCALE_OUT, executed.target(), "no started instance to remove");
        }
        docker.stopContainerCmd(name).withTimeout(10).exec();
        docker.removeContainerCmd(name).withForce(true).exec();
        return new ExecutionResult(ActionType.SCALE_OUT, executed.target(), true, "removed " + name);
    }
}
