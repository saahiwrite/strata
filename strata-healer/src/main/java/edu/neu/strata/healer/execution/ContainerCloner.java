package edu.neu.strata.healer.execution;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ContainerNetwork;

// Starts a copy of an existing container, possibly from a different image
@Component
public class ContainerCloner {

    private final DockerClient docker;

    public ContainerCloner(DockerClient docker) {
        this.docker = docker;
    }

    public String start(InspectContainerResponse source, String name, String image,
                        Map<String, String> labels, String serviceAlias) {
        String network = source.getHostConfig().getNetworkMode();
        String[] env = source.getConfig().getEnv() == null ? new String[0] : source.getConfig().getEnv();

        String id = docker.createContainerCmd(image)
                .withName(name)
                .withEnv(env)
                .withLabels(labels)
                .withHostConfig(source.getHostConfig())
                .exec()
                .getId();

        // other containers reach the service by its name, so the copy needs that alias too
        docker.disconnectFromNetworkCmd().withContainerId(id).withNetworkId(network).withForce(true).exec();
        docker.connectToNetworkCmd().withContainerId(id).withNetworkId(network)
                .withContainerNetwork(new ContainerNetwork().withAliases(serviceAlias)).exec();
        docker.startContainerCmd(id).exec();
        return id;
    }
}
