package edu.neu.strata.healer.execution;

import java.io.IOException;

import com.github.dockerjava.transport.DockerHttpClient;

/**
 * Sends every Docker API request on a new connection ({@code Connection: close}).
 *
 * <p>The healer reaches Docker through docker-proxy (HAProxy, #63), which closes a
 * keep-alive connection after 10s idle. The pooled HTTP client doesn't notice, reuses
 * the dead connection for the next call, and that call fails with
 * {@code NoHttpResponseException}. Incidents are minutes apart, so this hit the first
 * restart of almost every incident. A new connection per call costs a few milliseconds
 * on the local network and avoids it.
 *
 * <p>Hijacked requests (attach/exec streams) are passed through unchanged; the healer
 * doesn't make any, and they need their connection kept open.
 */
class FreshConnectionDockerHttpClient implements DockerHttpClient {

    private final DockerHttpClient delegate;

    FreshConnectionDockerHttpClient(DockerHttpClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public Response execute(Request request) {
        if (request.hijackedInput() != null) {
            return delegate.execute(request);
        }
        return delegate.execute(Request.builder().from(request).putHeader("Connection", "close").build());
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
