package edu.neu.strata.healer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AlertTest {

    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");

    @Test
    void keepsFieldsAndTrimsNames() {
        Alert alert = new Alert(" ServiceDown ", "order-service ", "strata-order-service-1", "critical", NOW);

        assertEquals("ServiceDown", alert.alertName());
        assertEquals("order-service", alert.service());
        assertEquals("strata-order-service-1", alert.container());
        assertEquals("critical", alert.severity());
        assertEquals(NOW, alert.startsAt());
    }

    @Test
    void containerAndSeverityAreOptional() {
        Alert alert = new Alert("HighErrorRate", "inventory-service", null, null, NOW);

        assertNull(alert.container());
        assertNull(alert.severity());
    }

    @Test
    void rejectsMissingRequiredFields() {
        assertThrows(IllegalArgumentException.class, () -> new Alert(" ", "order-service", null, null, NOW));
        assertThrows(IllegalArgumentException.class, () -> new Alert("ServiceDown", null, null, null, NOW));
        assertThrows(NullPointerException.class, () -> new Alert("ServiceDown", "order-service", null, null, null));
    }
}
