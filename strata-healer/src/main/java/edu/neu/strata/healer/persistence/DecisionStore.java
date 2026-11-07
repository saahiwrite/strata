package edu.neu.strata.healer.persistence;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.neu.strata.healer.model.Diagnosis;
import edu.neu.strata.healer.model.RiskAssessment;

// Write side of the decisions table
@Repository
public class DecisionStore {

    private final JdbcClient jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public DecisionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long incidentId, Diagnosis diagnosis, String rejectionReason, RiskAssessment risk, String outcome) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO decisions (incident_id, rule_id, action, target, confidence, allowed, rejection_reason, "
                        + "risk_score, risk_factors, autonomy_level, outcome, decided_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(incidentId, diagnosis.ruleId(), diagnosis.proposedAction().name(), diagnosis.target(),
                        diagnosis.confidence(), rejectionReason == null, rejectionReason,
                        risk == null ? null : risk.score(),
                        risk == null ? null : factorsAsJson(risk),
                        risk == null ? null : risk.level().name(),
                        outcome, OffsetDateTime.now(ZoneOffset.UTC))
                .update(keys, "id");
        return keys.getKey().longValue();
    }

    public void updateOutcome(long decisionId, String outcome) {
        jdbc.sql("UPDATE decisions SET outcome = ? WHERE id = ?")
                .params(outcome, decisionId)
                .update();
    }

    private String factorsAsJson(RiskAssessment risk) {
        try {
            return json.writeValueAsString(risk.factors());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not write risk factors", e);
        }
    }
}
