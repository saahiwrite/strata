package edu.neu.strata.healer.detection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import edu.neu.strata.healer.model.Alert;
import edu.neu.strata.healer.persistence.IncidentStore;
import edu.neu.strata.healer.persistence.TestDatabase;

class IncidentServiceTest {

    private final TestDatabase db = new TestDatabase();
    private final JdbcClient jdbc = JdbcClient.create(db.dataSource);
    private final IncidentService service = new IncidentService(new IncidentStore(jdbc));

    private Alert alert(String name, String svc) {
        return new Alert(name, svc, "strata-" + svc + "-1", "critical", Instant.parse("2026-11-06T10:00:00Z"));
    }

    private String statusOf(long id) {
        return jdbc.sql("SELECT status FROM incidents WHERE id = ?").param(id).query(String.class).single();
    }

    @Test
    void opensIncidentFromAlert() {
        Incident incident = service.open(alert("ServiceDown", "order-service"));

        assertEquals("order-service", incident.alert().service());
        assertEquals("OPEN", statusOf(incident.id()));
        assertTrue(service.findOpen("order-service", "ServiceDown").isPresent());
    }

    @Test
    void sameAlertDoesNotOpenSecondIncident() {
        Incident first = service.open(alert("ServiceDown", "order-service"));
        Incident second = service.open(alert("ServiceDown", "order-service"));

        assertEquals(first.id(), second.id());
    }

    @Test
    void differentServiceOpensNewIncident() {
        Incident first = service.open(alert("ServiceDown", "order-service"));
        Incident second = service.open(alert("ServiceDown", "inventory-service"));

        assertNotEquals(first.id(), second.id());
    }

    @Test
    void resolvedIncidentIsClosedAndAllowsNewOne() {
        Incident first = service.open(alert("ServiceDown", "order-service"));
        service.resolve(first.id());

        assertEquals("RESOLVED", statusOf(first.id()));
        assertNotNull(jdbc.sql("SELECT closed_at FROM incidents WHERE id = ?").param(first.id())
                .query(Object.class).single());
        assertTrue(service.findOpen("order-service", "ServiceDown").isEmpty());

        Incident second = service.open(alert("ServiceDown", "order-service"));
        assertNotEquals(first.id(), second.id());
    }

    @Test
    void escalateMarksIncidentEscalated() {
        Incident incident = service.open(alert("HighErrorRate", "order-service"));
        service.escalate(incident.id());

        assertEquals("ESCALATED", statusOf(incident.id()));
    }
}
