package edu.neu.strata.healer.execution;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;

// Finds the containers that belong to one Docker Compose service
@Component
public class ComposeServices {

    static final String SERVICE_LABEL = "com.docker.compose.service";
    static final String PROJECT_LABEL = "com.docker.compose.project";
    static final String NUMBER_LABEL = "com.docker.compose.container-number";

    private final DockerClient docker;

    public ComposeServices(DockerClient docker) {
        this.docker = docker;
    }

    public List<Container> all(String project, String service) {
        return docker.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(Map.of(PROJECT_LABEL, project, SERVICE_LABEL, service))
                .exec();
    }

    public List<Container> running(String project, String service) {
        return all(project, service).stream()
                .filter(c -> "running".equals(c.getState()))
                .toList();
    }

    public int nextNumber(List<Container> containers) {
        int highest = 1;
        for (Container c : containers) {
            String number = c.getLabels() == null ? null : c.getLabels().get(NUMBER_LABEL);
            if (number != null && number.matches("\\d+")) {
                highest = Math.max(highest, Integer.parseInt(number));
            }
        }
        return highest + 1;
    }
}
