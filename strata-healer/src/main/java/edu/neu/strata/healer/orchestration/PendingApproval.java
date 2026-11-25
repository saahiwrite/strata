package edu.neu.strata.healer.orchestration;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonRawValue;

// What an engineer sees before saying yes or no
public record PendingApproval(
        long decisionId,
        long incidentId,
        String service,
        String ruleId,
        String action,
        String target,
        double confidence,
        Integer riskScore,
        @JsonRawValue String riskFactors,
        String preview,
        Instant requestedAt,
        Instant expiresAt) {
}
