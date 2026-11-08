package edu.neu.strata.healer.model;

import java.time.Instant;
import java.util.Objects;

/**
 * One firing alert from Alertmanager, reduced to what the healer needs.
 *
 * @param alertName the Prometheus rule that fired, e.g. {@code ServiceDown}
 * @param service   the logical service, e.g. {@code order-service}
 * @param container the Docker container the alert points at (may be null when the alert has no such label)
 * @param severity  the alert's severity label, e.g. {@code critical}
 * @param startsAt  when the alert started firing
 */
public record Alert(String alertName, String service, String container, String severity, Instant startsAt) {

    public Alert {
        alertName = requireText(alertName, "alertName");
        service = requireText(service, "service");
        Objects.requireNonNull(startsAt, "startsAt");
    }

    static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
