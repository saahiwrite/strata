package edu.neu.strata.healer.api;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import edu.neu.strata.healer.detection.AlertParser;
import edu.neu.strata.healer.detection.IncidentService;

@RestController
@RequestMapping("/api/alerts")
public class AlertWebhookController {

    private final AlertParser alertParser;
    private final IncidentService incidentService;

    public AlertWebhookController(AlertParser alertParser, IncidentService incidentService) {
        this.alertParser = alertParser;
        this.incidentService = incidentService;
    }

    @PostMapping
    public ResponseEntity<Void> receive(@RequestBody Map<String, Object> payload) {
        alertParser.parse(payload).forEach(incidentService::open);
        return ResponseEntity.accepted().build();
    }
}
