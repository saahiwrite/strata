package edu.neu.strata.healer.detection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import edu.neu.strata.healer.model.Alert;

class IncidentServiceTest {

    private final IncidentService service = new IncidentService();

    private Alert alert(String name, String svc) {
        return new Alert(name, svc, svc + "-1", "critical", Instant.parse("2026-11-06T10:00:00Z"));
    }

    @Test
    void opensIncidentFromAlert() {
        Incident incident = service.open(alert("ServiceDown", "order-service"));

        assertEquals(IncidentStatus.OPEN, incident.getStatus());
        assertEquals("order-service", incident.getAlert().service());
        assertTrue(service.find(incident.getId()).isPresent());
    }

    @Test
    void sameAlertDoesNotOpenSecondIncident() {
        Incident first = service.open(alert("ServiceDown", "order-service"));
        Incident second = service.open(alert("ServiceDown", "order-service"));

        assertSame(first, second);
        assertEquals(1, service.findAll().size());
    }

    @Test
    void differentServiceOpensNewIncident() {
        service.open(alert("ServiceDown", "order-service"));
        service.open(alert("ServiceDown", "inventory-service"));

        assertEquals(2, service.findAll().size());
    }

    @Test
    void resolvedIncidentIsClosedAndAllowsNewOne() {
        Incident first = service.open(alert("ServiceDown", "order-service"));
        service.resolve(first.getId());

        assertEquals(IncidentStatus.RESOLVED, first.getStatus());
        assertNotNull(first.getClosedAt());

        Incident second = service.open(alert("ServiceDown", "order-service"));
        assertEquals(2, service.findAll().size());
        assertEquals(IncidentStatus.OPEN, second.getStatus());
    }

    @Test
    void escalateMarksIncidentEscalated() {
        Incident incident = service.open(alert("HighErrorRate", "order-service"));
        service.escalate(incident.getId());

        assertEquals(IncidentStatus.ESCALATED, incident.getStatus());
    }
}
