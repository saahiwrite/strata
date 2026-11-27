package edu.neu.strata.healer.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import edu.neu.strata.healer.verification.PrometheusClient.Sample;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PrometheusClientTest {

    private MockRestServiceServer server;
    private PrometheusClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://prometheus:9090");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new PrometheusClient(builder.build());
    }

    @Test
    void readsVectorResult() {
        String promql = "up{service=\"order-service\"}";
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://prometheus:9090/api/v1/query?")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("query", URLEncoder.encode(promql, StandardCharsets.UTF_8).replace("+", "%20")))
                .andRespond(withSuccess("""
                        {"status":"success","data":{"resultType":"vector","result":[
                          {"metric":{"__name__":"up","service":"order-service","container":"strata-order-service-1"},
                           "value":[1794027000.123,"1"]},
                          {"metric":{"service":"order-service","container":"strata-order-service-2"},
                           "value":[1794027000.123,"0"]}
                        ]}}""", MediaType.APPLICATION_JSON));

        List<Sample> samples = client.query(promql);

        assertEquals(2, samples.size());
        assertEquals("strata-order-service-1", samples.get(0).labels().get("container"));
        assertEquals(1.0, samples.get(0).value());
        assertEquals(0.0, samples.get(1).value());
        server.verify();
    }

    @Test
    void emptyResultIsEmptyList() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://prometheus:9090/api/v1/query")))
                .andRespond(withSuccess("{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}",
                        MediaType.APPLICATION_JSON));

        assertTrue(client.query("up{service=\"nothing\"}").isEmpty());
    }

    @Test
    void readsNaN() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://prometheus:9090/api/v1/query")))
                .andRespond(withSuccess("""
                        {"status":"success","data":{"resultType":"vector","result":[
                          {"metric":{},"value":[1794027000,"NaN"]}]}}""", MediaType.APPLICATION_JSON));

        Sample sample = client.query("sum(rate(x[1m])) / sum(rate(y[1m]))").get(0);

        assertTrue(Double.isNaN(sample.value()));
        assertEquals(Map.of(), sample.labels());
    }

    @Test
    void badQueryThrowsWithPrometheusError() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://prometheus:9090/api/v1/query")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"error\",\"errorType\":\"bad_data\",\"error\":\"parse error\"}"));

        PrometheusException e = assertThrows(PrometheusException.class, () -> client.query("up{"));
        assertTrue(e.getMessage().contains("up{"));
        assertTrue(e.getCause().getMessage().contains("parse error"), e.getCause().getMessage());
    }

    @Test
    void serverErrorThrows() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://prometheus:9090/api/v1/query")))
                .andRespond(withServerError());

        assertThrows(PrometheusException.class, () -> client.query("up"));
    }

    @Test
    void nonVectorResultThrows() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://prometheus:9090/api/v1/query")))
                .andRespond(withSuccess("{\"status\":\"success\",\"data\":{\"resultType\":\"scalar\",\"result\":[1,\"1\"]}}",
                        MediaType.APPLICATION_JSON));

        assertThrows(PrometheusException.class, () -> client.query("1"));
    }

    @Test
    void emptyBodyThrows() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://prometheus:9090/api/v1/query")))
                .andRespond(withSuccess());

        PrometheusException e = assertThrows(PrometheusException.class, () -> client.query("up"));
        assertTrue(e.getMessage().contains("empty response"), e.getMessage());
    }

    @Test
    void errorStatusWithErrorFieldThrowsWithThatError() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://prometheus:9090/api/v1/query")))
                .andRespond(withSuccess("{\"status\":\"error\",\"errorType\":\"timeout\",\"error\":\"query timed out\"}",
                        MediaType.APPLICATION_JSON));

        PrometheusException e = assertThrows(PrometheusException.class, () -> client.query("up"));
        assertTrue(e.getMessage().contains("query timed out"), e.getMessage());
    }

    @Test
    void errorStatusWithoutErrorFieldThrowsUnknownError() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://prometheus:9090/api/v1/query")))
                .andRespond(withSuccess("{\"status\":\"error\"}", MediaType.APPLICATION_JSON));

        PrometheusException e = assertThrows(PrometheusException.class, () -> client.query("up"));
        assertTrue(e.getMessage().contains("unknown error"), e.getMessage());
    }

    @Test
    void missingStatusThrows() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://prometheus:9090/api/v1/query")))
                .andRespond(withSuccess("{\"data\":{\"resultType\":\"vector\",\"result\":[]}}",
                        MediaType.APPLICATION_JSON));

        assertThrows(PrometheusException.class, () -> client.query("up"));
    }

    @Test
    void readsInfinity() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://prometheus:9090/api/v1/query")))
                .andRespond(withSuccess("""
                        {"status":"success","data":{"resultType":"vector","result":[
                          {"metric":{"le":"a"},"value":[1794027000,"+Inf"]},
                          {"metric":{"le":"b"},"value":[1794027000,"-Inf"]}]}}""", MediaType.APPLICATION_JSON));

        List<Sample> samples = client.query("histogram_quantile(0.99, x)");
        assertEquals(Double.POSITIVE_INFINITY, samples.get(0).value());
        assertEquals(Double.NEGATIVE_INFINITY, samples.get(1).value());
    }

    @Test
    void missingValueThrowsPrometheusException() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://prometheus:9090/api/v1/query")))
                .andRespond(withSuccess("""
                        {"status":"success","data":{"resultType":"vector","result":[
                          {"metric":{"service":"order-service"}}]}}""", MediaType.APPLICATION_JSON));

        PrometheusException e = assertThrows(PrometheusException.class, () -> client.query("up"));
        assertTrue(e.getCause() instanceof NumberFormatException);
    }

    @Test
    void nonNumericValueThrowsPrometheusException() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://prometheus:9090/api/v1/query")))
                .andRespond(withSuccess("""
                        {"status":"success","data":{"resultType":"vector","result":[
                          {"metric":{},"value":[1794027000,"abc"]}]}}""", MediaType.APPLICATION_JSON));

        PrometheusException e = assertThrows(PrometheusException.class, () -> client.query("up"));
        assertTrue(e.getMessage().contains("'abc'"), e.getMessage());
    }

    @Test
    void unreachablePrometheusThrows() {
        PrometheusClient unreachable = PrometheusClient.create("http://127.0.0.1:1", java.time.Duration.ofMillis(500));

        assertThrows(PrometheusException.class, () -> unreachable.query("up"));
    }
}
