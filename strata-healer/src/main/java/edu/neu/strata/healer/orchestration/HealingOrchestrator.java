package edu.neu.strata.healer.orchestration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import edu.neu.strata.healer.detection.Incident;
import edu.neu.strata.healer.model.Alert;
import edu.neu.strata.healer.model.Diagnosis;
import edu.neu.strata.healer.model.RiskAssessment;

// Keeps the order of the stages: diagnose -> assess -> execute (or wait for a human)
@Component
public class HealingOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(HealingOrchestrator.class);

    private final IncidentLifecycle lifecycle;
    private final DiagnosisStage diagnosisStage;
    private final AssessmentStage assessmentStage;
    private final ExecutionStage executionStage;
    private final Executor background;

    @Autowired
    public HealingOrchestrator(IncidentLifecycle lifecycle, DiagnosisStage diagnosisStage,
                               AssessmentStage assessmentStage, ExecutionStage executionStage) {
        this(lifecycle, diagnosisStage, assessmentStage, executionStage,
                Executors.newVirtualThreadPerTaskExecutor());
    }

    HealingOrchestrator(IncidentLifecycle lifecycle, DiagnosisStage diagnosisStage,
                        AssessmentStage assessmentStage, ExecutionStage executionStage, Executor background) {
        this.lifecycle = lifecycle;
        this.diagnosisStage = diagnosisStage;
        this.assessmentStage = assessmentStage;
        this.executionStage = executionStage;
        this.background = background;
    }

    // Called by the webhook. Opens the incident and heals it in the background.
    public void handle(Alert alert) {
        lifecycle.openIfNew(alert).ifPresentOrElse(
                incident -> background.execute(() -> heal(incident)),
                () -> log.info("{} on {} is already being handled", alert.alertName(), alert.service()));
    }

    public void heal(Incident incident) {
        try {
            run(incident);
        } catch (RuntimeException e) {
            log.error("Healing incident {} failed", incident.id(), e);
            lifecycle.escalateIfOpen(incident, "unexpected error: " + e.getMessage());
        }
    }

    private void run(Incident incident) {
        lifecycle.begin(incident);

        DiagnosisStage.Result diagnosis = diagnosisStage.diagnose(incident);
        if (!diagnosis.found()) {
            lifecycle.escalate(incident, diagnosis.reason());
            return;
        }

        Diagnosis proposed = diagnosis.diagnosis().get();
        AssessmentStage.Result assessment = assessmentStage.assess(incident, proposed);
        if (!assessment.allowed()) {
            lifecycle.reject(incident, proposed, assessment.rejectionReason());
            return;
        }

        RiskAssessment risk = assessment.risk();
        Decision decision = lifecycle.propose(incident, proposed, risk);
        switch (risk.level()) {
            case L2_APPROVAL -> lifecycle.awaitApproval(decision);
            case L3_HUMAN_ONLY -> lifecycle.handToHuman(incident, decision, "risk level needs a human");
            case L0_AUTO, L1_AUTO_UNDO -> executionStage.run(incident, proposed, decision);
        }
    }
}
