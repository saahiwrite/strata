package edu.neu.strata.healer.detection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import edu.neu.strata.healer.model.Alert;

class AlertParserTest {

    private final AlertParser parser = new AlertParser();

    private Map<String, Object> firing(Map<String, Object> labels) {
        return Map.of("status", "firing", "alerts", List.of(
                Map.of("status", "firing", "labels", labels, "startsAt", "2026-11-06T10:00:00Z")));
    }

    @Test
    void parsesFiringAlert() {
        Map<String, Object> payload = firing(Map.of(
                "alertname", "ServiceDown",
                "service", "order-service",
                "container", "order-service-1",
                "severity", "critical"));

        List<Alert> alerts = parser.parse(payload);

        assertEquals(1, alerts.size());
        Alert alert = alerts.get(0);
        assertEquals("ServiceDown", alert.alertName());
        assertEquals("order-service", alert.service());
        assertEquals("order-service-1", alert.container());
        assertEquals("critical", alert.severity());
        assertEquals(Instant.parse("2026-11-06T10:00:00Z"), alert.startsAt());
    }

    @Test
    void containerCanBeMissing() {
        List<Alert> alerts = parser.parse(firing(Map.of(
                "alertname", "HighErrorRate", "service", "inventory-service", "severity", "warning")));

        assertEquals(1, alerts.size());
        assertNull(alerts.get(0).container());
    }

    @Test
    void skipsResolvedAlerts() {
        Map<String, Object> payload = Map.of("alerts", List.of(
                Map.of("status", "resolved",
                        "labels", Map.of("alertname", "ServiceDown", "service", "order-service"),
                        "startsAt", "2026-11-06T10:00:00Z")));

        assertTrue(parser.parse(payload).isEmpty());
    }

    @Test
    void skipsAlertsWithoutServiceLabel() {
        List<Alert> alerts = parser.parse(firing(Map.of("alertname", "ServiceDown")));

        assertTrue(alerts.isEmpty());
    }

    @Test
    void handlesEmptyOrMissingAlertList() {
        assertTrue(parser.parse(Map.of()).isEmpty());
        assertTrue(parser.parse(null).isEmpty());
    }

    @Test
    void keepsValidAlertsWhenOneIsBroken() {
        Map<String, Object> payload = Map.of("alerts", List.of(
                Map.of("labels", Map.of("alertname", "ServiceDown")),
                Map.of("labels", Map.of("alertname", "ServiceDown", "service", "order-service"),
                        "startsAt", "2026-11-06T10:00:00Z")));

        assertEquals(1, parser.parse(payload).size());
    }
}
