package edu.neu.strata.healer.execution;

import java.time.Duration;
import java.util.ArrayList;
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
import edu.neu.strata.healer.verification.RecoveryVerifier;

// Puts the previous image back on every instance of a service, one instance first (canary)
@Component
public class RollbackAction {

    private final DockerClient docker;
    private final ComposeServices services;
    private final ContainerCloner cloner;
    private final RecoveryVerifier verifier;
    private final String previousImageLabel;
    private final Duration canaryTimeout;

    public RollbackAction(DockerClient docker, ComposeServices services, ContainerCloner cloner,
                          RecoveryVerifier verifier,
                          @Value("${strata.rollback.previous-image-label:strata.previous-image}") String previousImageLabel,
                          @Value("${strata.canary.verify-timeout-seconds:60}") long canarySeconds) {
        this.docker = docker;
        this.services = services;
        this.cloner = cloner;
        this.verifier = verifier;
        this.previousImageLabel = previousImageLabel;
        this.canaryTimeout = Duration.ofSeconds(canarySeconds);
    }

    public ExecutionResult run(String target) {
        InspectContainerResponse source = docker.inspectContainerCmd(target).exec();
        Map<String, String> labels = source.getConfig().getLabels();
        String service = labels == null ? null : labels.get(ComposeServices.SERVICE_LABEL);
        String project = labels == null ? null : labels.get(ComposeServices.PROJECT_LABEL);
        if (service == null || project == null) {
            return ExecutionResult.failed(ActionType.ROLLBACK, target, target + " is not a Compose container");
        }

        List<InspectContainerResponse> instances = instancesWithTargetFirst(project, service, source);

        // check every instance before touching any of them
        for (InspectContainerResponse instance : instances) {
            if (previousImage(instance) == null) {
                return ExecutionResult.failed(ActionType.ROLLBACK, target,
                        trimName(instance) + " has no " + previousImageLabel + " label, nothing to roll back to");
            }
        }

        replace(instances.get(0), service);
        if (instances.size() > 1) {
            if (!verifier.verify(service, canaryTimeout)) {
                return ExecutionResult.failed(ActionType.ROLLBACK, target,
                        "canary " + trimName(instances.get(0)) + " did not recover, the other instances were left alone");
            }
            for (InspectContainerResponse rest : instances.subList(1, instances.size())) {
                replace(rest, service);
            }
        }
        return new ExecutionResult(ActionType.ROLLBACK, target, true,
                "rolled back " + instances.size() + " instance(s) of " + service);
    }

    private List<InspectContainerResponse> instancesWithTargetFirst(String project, String service,
                                                                    InspectContainerResponse target) {
        List<InspectContainerResponse> instances = new ArrayList<>();
        instances.add(target);
        for (Container c : services.running(project, service)) {
            if (!c.getId().equals(target.getId())) {
                instances.add(docker.inspectContainerCmd(c.getId()).exec());
            }
        }
        return instances;
    }

    private void replace(InspectContainerResponse instance, String service) {
        String image = previousImage(instance);
        String name = trimName(instance);

        docker.stopContainerCmd(instance.getId()).withTimeout(10).exec();
        docker.removeContainerCmd(instance.getId()).withForce(true).exec();

        // drop the label so a second rollback can't flip back to the bad image
        Map<String, String> labels = new HashMap<>(instance.getConfig().getLabels());
        labels.remove(previousImageLabel);
        cloner.start(instance, name, image, labels, service);
    }

    private String previousImage(InspectContainerResponse instance) {
        Map<String, String> labels = instance.getConfig().getLabels();
        return labels == null ? null : labels.get(previousImageLabel);
    }

    private String trimName(InspectContainerResponse instance) {
        String name = instance.getName();
        return name.startsWith("/") ? name.substring(1) : name;
    }
}
