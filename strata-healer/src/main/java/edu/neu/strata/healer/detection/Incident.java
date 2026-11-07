package edu.neu.strata.healer.detection;

import java.time.Instant;

import edu.neu.strata.healer.model.Alert;

public record Incident(long id, Alert alert, Instant openedAt) {
}
