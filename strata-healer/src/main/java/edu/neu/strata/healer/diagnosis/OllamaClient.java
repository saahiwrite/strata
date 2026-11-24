package edu.neu.strata.healer.diagnosis;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Calls a local Ollama server's chat API ({@code POST /api/chat}). The reply is forced to JSON
 * ({@code format: json}) with temperature 0, so the same alert gets the same answer.
 * Any failure (Ollama down, model missing, timeout) returns empty, and the incident is escalated.
 */
public class OllamaClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);

    private final RestClient restClient;
    private final String model;

    /** A client with connect and read timeouts; small models on a laptop CPU can take tens of seconds. */
    public OllamaClient(RestClient.Builder builder, String baseUrl, String model, Duration timeout) {
        this(builder.requestFactory(requestFactory(timeout)), baseUrl, model);
    }

    // For tests: the builder may already be bound to a mock server.
    OllamaClient(RestClient.Builder builder, String baseUrl, String model) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.model = model;
    }

    @Override
    public Optional<String> complete(String system, String user) {
        Map<String, Object> request = Map.of(
                "model", model,
                "stream", false,
                "format", "json",
                "options", Map.of("temperature", 0),
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", user)));
        try {
            JsonNode response = restClient.post()
                    .uri("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode content = response == null ? null : response.path("message").path("content");
            if (content == null || !content.isTextual() || content.asText().isBlank()) {
                log.warn("Ollama returned no message content");
                return Optional.empty();
            }
            return Optional.of(content.asText());
        } catch (Exception e) {
            log.warn("Could not reach Ollama model {}: {}", model, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public String model() {
        return model;
    }

    private static JdkClientHttpRequestFactory requestFactory(Duration timeout) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        return factory;
    }
}
