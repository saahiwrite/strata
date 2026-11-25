package edu.neu.strata.healer.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient.Request;

class FreshConnectionDockerHttpClientTest {

    /** Remembers what it was asked to send. */
    static class RecordingClient implements DockerHttpClient {
        final List<Request> sent = new ArrayList<>();
        boolean closed;

        @Override
        public Response execute(Request request) {
            sent.add(request);
            return null;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    void everyRequestAsksForANewConnection() {
        RecordingClient inner = new RecordingClient();
        FreshConnectionDockerHttpClient client = new FreshConnectionDockerHttpClient(inner);

        client.execute(Request.builder().method(Request.Method.POST)
                .path("/containers/strata-order-service-1/restart").build());

        Request sent = inner.sent.get(0);
        assertEquals("close", sent.headers().get("Connection"));
        assertEquals("POST", sent.method());
        assertEquals("/containers/strata-order-service-1/restart", sent.path());
    }

    @Test
    void keepsTheOtherHeadersAndTheBody() {
        RecordingClient inner = new RecordingClient();
        FreshConnectionDockerHttpClient client = new FreshConnectionDockerHttpClient(inner);

        client.execute(Request.builder().method(Request.Method.POST).path("/containers/create")
                .putHeader("Content-Type", "application/json")
                .bodyBytes("{\"Image\":\"strata/order-service:1.0\"}".getBytes()).build());

        Request sent = inner.sent.get(0);
        assertEquals("application/json", sent.headers().get("Content-Type"));
        assertEquals("close", sent.headers().get("Connection"));
        assertEquals("{\"Image\":\"strata/order-service:1.0\"}", new String(sent.bodyBytes()));
    }

    @Test
    void leavesHijackedStreamsAlone() {
        RecordingClient inner = new RecordingClient();
        FreshConnectionDockerHttpClient client = new FreshConnectionDockerHttpClient(inner);
        Request attach = Request.builder().method(Request.Method.POST).path("/containers/x/attach")
                .hijackedInput(new ByteArrayInputStream(new byte[0])).build();

        client.execute(attach);

        assertSame(attach, inner.sent.get(0));
    }

    @Test
    void closeClosesTheRealClient() throws IOException {
        RecordingClient inner = new RecordingClient();
        new FreshConnectionDockerHttpClient(inner).close();
        assertTrue(inner.closed);
    }
}
