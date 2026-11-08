package edu.neu.strata.healer.detection;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;

import edu.neu.strata.healer.model.Alert;
import edu.neu.strata.healer.persistence.IncidentStore;

@Service
public class IncidentService {

    private final IncidentStore store;

    public IncidentService(IncidentStore store) {
        this.store = store;
    }

    // Alertmanager repeats firing alerts, so reuse the open incident if there is one
    public synchronized Incident open(Alert alert) {
        return store.findOpen(alert.service(), alert.alertName())
                .orElseGet(() -> store.insert(alert, alert.startsAt()));
    }

    public Optional<Incident> findOpen(String service, String alertName) {
        return store.findOpen(service, alertName);
    }

    public void resolve(long id) {
        store.close(id, IncidentStatus.RESOLVED, Instant.now());
    }

    public void escalate(long id) {
        store.close(id, IncidentStatus.ESCALATED, Instant.now());
    }
}
