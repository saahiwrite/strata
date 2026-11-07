package edu.neu.strata.healer.detection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;

import edu.neu.strata.healer.model.Alert;

@Service
public class IncidentService {

    private final Map<Long, Incident> incidents = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);

    // Alertmanager repeats firing alerts, so reuse the open incident if there is one
    public synchronized Incident open(Alert alert) {
        Optional<Incident> existing = findOpen(alert.service(), alert.alertName());
        if (existing.isPresent()) {
            return existing.get();
        }
        Incident incident = new Incident(nextId.getAndIncrement(), alert, alert.startsAt());
        incidents.put(incident.getId(), incident);
        return incident;
    }

    public Optional<Incident> find(long id) {
        return Optional.ofNullable(incidents.get(id));
    }

    public Optional<Incident> findOpen(String service, String alertName) {
        return incidents.values().stream()
                .filter(i -> i.getStatus() == IncidentStatus.OPEN)
                .filter(i -> i.getAlert().service().equals(service))
                .filter(i -> i.getAlert().alertName().equals(alertName))
                .findFirst();
    }

    public List<Incident> findAll() {
        List<Incident> all = new ArrayList<>(incidents.values());
        all.sort((a, b) -> Long.compare(a.getId(), b.getId()));
        return all;
    }

    public void resolve(long id) {
        find(id).ifPresent(i -> i.close(IncidentStatus.RESOLVED));
    }

    public void escalate(long id) {
        find(id).ifPresent(i -> i.close(IncidentStatus.ESCALATED));
    }
}
