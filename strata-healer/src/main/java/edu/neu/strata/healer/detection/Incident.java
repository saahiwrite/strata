package edu.neu.strata.healer.detection;

import java.time.Instant;

import edu.neu.strata.healer.model.Alert;

public class Incident {

    private final long id;
    private final Alert alert;
    private final Instant openedAt;
    private IncidentStatus status;
    private Instant closedAt;

    public Incident(long id, Alert alert, Instant openedAt) {
        this.id = id;
        this.alert = alert;
        this.openedAt = openedAt;
        this.status = IncidentStatus.OPEN;
    }

    public long getId() {
        return id;
    }

    public Alert getAlert() {
        return alert;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public IncidentStatus getStatus() {
        return status;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    void close(IncidentStatus newStatus) {
        this.status = newStatus;
        this.closedAt = Instant.now();
    }
}
