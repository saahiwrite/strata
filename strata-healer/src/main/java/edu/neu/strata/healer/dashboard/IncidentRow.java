package edu.neu.strata.healer.dashboard;

import edu.neu.strata.healer.persistence.DecisionRecord;
import edu.neu.strata.healer.persistence.IncidentRecord;

/**
 * One line of the dashboard's incident table: the incident and its latest decision, if any.
 *
 * @param latest the newest decision for the incident, or null when it was escalated before
 *               anything was proposed (for example when no rule matched)
 */
public record IncidentRow(IncidentRecord incident, DecisionRecord latest) {

    /** Diagnosis shown in the table: rule and proposed action, or a dash. */
    public String diagnosis() {
        if (latest == null) {
            return "—";
        }
        String rule = latest.ruleId() == null ? "no rule" : latest.ruleId();
        return rule + " → " + latest.action();
    }
}
