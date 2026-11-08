package edu.neu.strata.healer.detection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import edu.neu.strata.healer.model.Alert;

@Component
public class AlertParser {

    public List<Alert> parse(Map<String, Object> payload) {
        List<Alert> alerts = new ArrayList<>();
        if (payload == null || !(payload.get("alerts") instanceof List<?> rawAlerts)) {
            return alerts;
        }

        for (Object raw : rawAlerts) {
            if (!(raw instanceof Map<?, ?> entry)) {
                continue;
            }
            Alert alert = toAlert(entry);
            if (alert != null) {
                alerts.add(alert);
            }
        }
        return alerts;
    }

    private Alert toAlert(Map<?, ?> entry) {
        if ("resolved".equals(entry.get("status"))) {
            return null;
        }
        if (!(entry.get("labels") instanceof Map<?, ?> labels)) {
            return null;
        }

        try {
            return new Alert(
                    text(labels.get("alertname")),
                    text(labels.get("service")),
                    text(labels.get("container")),
                    text(labels.get("severity")),
                    startTime(entry.get("startsAt")));
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    private String text(Object value) {
        return value == null ? null : value.toString();
    }

    private Instant startTime(Object value) {
        if (value == null) {
            return Instant.now();
        }
        return Instant.parse(value.toString());
    }
}
