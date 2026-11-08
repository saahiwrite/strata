package edu.neu.strata.healer.verification;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Runs instant PromQL queries against the Prometheus HTTP API ({@code GET /api/v1/query}).
 * Only vector results are supported, which is what the recovery checks use.
 */
public class PrometheusClient {

    /** One series in a query result: its labels and current value. */
    public record Sample(Map<String, String> labels, double value) {
        public Sample {
            labels = Map.copyOf(labels);
        }
    }

    private final RestClient restClient;

    public PrometheusClient(RestClient restClient) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
    }

    /** A client for {@code baseUrl} (e.g. {@code http://prometheus:9090}) with connect and read timeouts. */
    public static PrometheusClient create(String baseUrl, Duration timeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return new PrometheusClient(RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build());
    }

    /**
     * Runs {@code promql} as an instant query. An empty list means no series matched.
     *
     * @throws PrometheusException if Prometheus can't be reached, returns an error, or returns a non-vector result
     */
    public List<Sample> query(String promql) {
        JsonNode body;
        try {
            body = restClient.get()
                    .uri(uri -> uri.path("/api/v1/query").queryParam("query", "{q}").build(promql))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new PrometheusException("query failed: " + promql, e);
        }
        if (body == null || !"success".equals(body.path("status").asText())) {
            String error = body == null ? "empty response" : body.path("error").asText("unknown error");
            throw new PrometheusException("query failed: " + promql + " (" + error + ")");
        }
        JsonNode data = body.path("data");
        if (!"vector".equals(data.path("resultType").asText())) {
            throw new PrometheusException("expected a vector result for " + promql + ", got " + data.path("resultType").asText());
        }
        List<Sample> samples = new ArrayList<>();
        for (JsonNode series : data.path("result")) {
            Map<String, String> labels = new LinkedHashMap<>();
            series.path("metric").fields().forEachRemaining(e -> labels.put(e.getKey(), e.getValue().asText()));
            // value is [unix time, "string value"]; Prometheus writes NaN and +Inf as strings too
            samples.add(new Sample(labels, Double.parseDouble(series.path("value").path(1).asText())));
        }
        return samples;
    }
}
