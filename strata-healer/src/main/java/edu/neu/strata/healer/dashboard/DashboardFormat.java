package edu.neu.strata.healer.dashboard;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Formatting helpers the templates use as {@code fmt}. Times are shown in UTC. */
public final class DashboardFormat {

    static final DashboardFormat INSTANCE = new DashboardFormat();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
            .withZone(ZoneOffset.UTC);

    private DashboardFormat() {
    }

    public String time(Instant instant) {
        return instant == null ? "—" : TIME.format(instant);
    }

    /** How long an incident took from opening to closing, e.g. {@code 1m 15s}; a dash while open. */
    public String duration(Instant from, Instant to) {
        if (from == null || to == null) {
            return "—";
        }
        long seconds = Math.max(0, Duration.between(from, to).toSeconds());
        return seconds < 60 ? seconds + "s" : (seconds / 60) + "m " + (seconds % 60) + "s";
    }

    /** {@code L2_APPROVAL} → {@code L2 approval}. */
    public String level(String level) {
        if (level == null) {
            return "—";
        }
        return level.replaceFirst("_", " ").replace('_', ' ').toLowerCase().replaceFirst("^l", "L");
    }

    public String percent(double fraction) {
        return Math.round(fraction * 100) + "%";
    }
}
