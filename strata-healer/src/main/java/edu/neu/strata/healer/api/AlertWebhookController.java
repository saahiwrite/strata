package edu.neu.strata.healer.api;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import edu.neu.strata.healer.detection.AlertParser;
import edu.neu.strata.healer.orchestration.HealingOrchestrator;

@RestController
@RequestMapping("/api/alerts")
public class AlertWebhookController {

    private final AlertParser alertParser;
    private final HealingOrchestrator orchestrator;

    public AlertWebhookController(AlertParser alertParser, HealingOrchestrator orchestrator) {
        this.alertParser = alertParser;
        this.orchestrator = orchestrator;
    }

    @PostMapping
    public ResponseEntity<Void> receive(@RequestBody Map<String, Object> payload) {
        alertParser.parse(payload).forEach(orchestrator::handle);
        return ResponseEntity.accepted().build();
    }
}
