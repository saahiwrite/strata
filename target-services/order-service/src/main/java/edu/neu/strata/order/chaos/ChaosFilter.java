package edu.neu.strata.order.chaos;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Applies the slow and error switches to business endpoints. {@code /chaos} and
 * {@code /actuator} are never affected, so the switches can always be turned off and
 * Prometheus can always scrape.
 */
@Component
public class ChaosFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ChaosFilter.class);

    private final ChaosState state;
    private final Counter injectedErrors;

    public ChaosFilter(ChaosState state, MeterRegistry registry) {
        this.state = state;
        this.injectedErrors = Counter.builder("chaos_injected_errors")
                .description("Requests failed by the error switch")
                .register(registry);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.startsWith("/chaos") || path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long latency = state.latencyMs();
        if (latency > 0) {
            log.warn("Slow response injected delayMs={} path={}", latency, request.getRequestURI());
            try {
                Thread.sleep(latency);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (state.shouldFail()) {
            injectedErrors.increment();
            log.error("Request failed: injected error path={} method={}",
                    request.getRequestURI(), request.getMethod(),
                    new IllegalStateException("Chaos error switch is on"));
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"Internal Server Error\",\"reason\":\"chaos error switch\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
