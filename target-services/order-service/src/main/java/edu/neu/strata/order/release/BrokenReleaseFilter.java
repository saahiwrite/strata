package edu.neu.strata.order.release;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Simulates a bad deployment (scenario S14). In a broken release, most business requests fail
 * with HTTP 500 and the same NullPointerException, from the moment the service starts. Health
 * checks stay UP, so the service looks alive and only the error rate shows the problem; that is
 * what makes rollback, not restart, the right fix. A restart keeps the same image and so stays
 * broken; {@code /chaos/reset} does not touch it either.
 */
public class BrokenReleaseFilter extends OncePerRequestFilter {

    static final String BUG = "Cannot invoke \"edu.neu.strata.order.pricing.PriceList.priceFor(String)\""
            + " because \"this.priceList\" is null";

    private static final Logger log = LoggerFactory.getLogger(BrokenReleaseFilter.class);

    private final ReleaseProperties release;

    public BrokenReleaseFilter(ReleaseProperties release, MeterRegistry registry) {
        this.release = release;
        Gauge.builder("service_release", () -> 1)
                .description("The running release of this service")
                .tag("version", release.version())
                .tag("broken", String.valueOf(release.broken()))
                .register(registry);
        if (release.broken()) {
            log.warn("Running release {}, which fails {}% of requests", release.version(),
                    Math.round(release.failureRate() * 100));
        } else {
            log.info("Running release {}", release.version());
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!release.broken()) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.startsWith("/actuator") || path.startsWith("/chaos");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        double rate = release.failureRate();
        if (rate >= 1 || ThreadLocalRandom.current().nextDouble() < rate) {
            log.error("Request failed in release {} path={}", release.version(), request.getRequestURI(),
                    new NullPointerException(BUG));
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"Internal Server Error\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
