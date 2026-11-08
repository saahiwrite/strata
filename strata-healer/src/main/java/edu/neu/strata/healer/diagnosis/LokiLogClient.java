package edu.neu.strata.healer.diagnosis;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

@Component
public class LokiLogClient {

    private static final Logger log = LoggerFactory.getLogger(LokiLogClient.class);

    private final RestClient restClient;

    public LokiLogClient(RestClient.Builder builder, @Value("${strata.loki.url}") String lokiUrl) {
        this.restClient = builder.baseUrl(lokiUrl).build();
    }

    // Returns the recent log lines of a container, or an empty list if Loki can't be reached
    public List<String> recentLogs(String container, Duration window, int limit) {
        String query = "{container=\"" + container + "\"}";
        long end = Instant.now().toEpochMilli() * 1_000_000L;
        long start = end - window.toNanos();

        try {
            JsonNode response = restClient.get()
                    .uri(uri -> uri.path("/loki/api/v1/query_range")
                            .queryParam("query", "{query}")
                            .queryParam("start", start)
                            .queryParam("end", end)
                            .queryParam("limit", limit)
                            .queryParam("direction", "backward")
                            .build(query))
                    .retrieve()
                    .body(JsonNode.class);
            return readLines(response);
        } catch (Exception e) {
            log.warn("Could not read logs for {} from Loki: {}", container, e.getMessage());
            return List.of();
        }
    }

    private List<String> readLines(JsonNode response) {
        List<String> lines = new ArrayList<>();
        if (response == null) {
            return lines;
        }
        for (JsonNode stream : response.path("data").path("result")) {
            for (JsonNode entry : stream.path("values")) {
                lines.add(entry.get(1).asText());
            }
        }
        return lines;
    }
}
