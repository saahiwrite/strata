package edu.neu.strata.healer.diagnosis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class LokiLogClientTest {

    private MockRestServiceServer server;
    private LokiLogClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new LokiLogClient(builder, "http://loki:3100");
    }

    @Test
    void readsLogLinesFromLokiResponse() {
        String body = """
                {"status":"success","data":{"resultType":"streams","result":[
                  {"stream":{"container":"strata-order-service-1"},
                   "values":[["1762423200000000000","first line"],["1762423201000000000","second line"]]}
                ]}}
                """;
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://loki:3100/loki/api/v1/query_range")))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<String> lines = client.recentLogs("strata-order-service-1", Duration.ofMinutes(5), 50);

        assertEquals(List.of("first line", "second line"), lines);
    }

    @Test
    void returnsEmptyListWhenLokiFails() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://loki:3100")))
                .andRespond(withServerError());

        assertTrue(client.recentLogs("strata-order-service-1", Duration.ofMinutes(5), 50).isEmpty());
    }
}
