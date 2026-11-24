package edu.neu.strata.healer.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import edu.neu.strata.healer.audit.InjectionReport;

/**
 * Read-only view of the prompt-injection defense (FR4).
 * {@code GET /api/security/injections}: incidents flagged, lines withheld, signals seen,
 * proposals the allow-list blocked and actions that still ran on flagged incidents.
 */
@RestController
@RequestMapping("/api/security")
public class SecurityController {

    private final InjectionReport report;

    public SecurityController(InjectionReport report) {
        this.report = report;
    }

    @GetMapping("/injections")
    public InjectionReport.Stats injections() {
        return report.stats();
    }
}
