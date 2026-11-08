package edu.neu.strata.healer.persistence;

import java.time.Instant;

/**
 * One row of {@code incidents}, as the incidents API shows it.
 *
 * @param status {@code OPEN}, {@code RESOLVED} or {@code ESCALATED}
 * @param closedAt null while the incident is open
 */
public record IncidentRecord(
        long id,
        String alertName,
        String service,
        String container,
        String severity,
        String status,
        Instant openedAt,
        Instant closedAt) {
}
