package edu.neu.strata.healer.dashboard;

import edu.neu.strata.healer.audit.AuditEntry;
import edu.neu.strata.healer.audit.AuditEventType;
import edu.neu.strata.healer.audit.AuditLogService;
import edu.neu.strata.healer.audit.ChainVerification;
import edu.neu.strata.healer.model.ActionType;
import edu.neu.strata.healer.persistence.DecisionRecord;
import edu.neu.strata.healer.persistence.IncidentRecord;
import edu.neu.strata.healer.persistence.IncidentRepository;
import edu.neu.strata.healer.trust.TrustRecord;
import edu.neu.strata.healer.trust.TrustService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(controllers = DashboardController.class)
class DashboardControllerTest {

    private static final Instant T0 = Instant.parse("2026-11-20T10:00:00Z");

    private static final IncidentRecord CRASH = new IncidentRecord(1, "ServiceDown", "order-service",
            "strata-order-service-1", "critical", "RESOLVED", T0, T0.plusSeconds(15));
    private static final IncidentRecord BAD_DEPLOY = new IncidentRecord(2, "HighErrorRate", "order-service",
            "strata-order-service-1", "warning", "OPEN", T0.plusSeconds(600), null);
    private static final IncidentRecord UNKNOWN = new IncidentRecord(3, "HighErrorRate", "inventory-service",
            "strata-inventory-service-1", "warning", "ESCALATED", T0.plusSeconds(900), T0.plusSeconds(901));

    private static final DecisionRecord RESTARTED = new DecisionRecord(10, 1, "service-down-restart", "RESTART",
            "strata-order-service-1", 0.9, true, null, 9, "{\"reversibility\":3,\"blastRadius\":5}",
            "L0_AUTO", "RECOVERED", T0.plusSeconds(5));
    private static final DecisionRecord ROLLBACK = new DecisionRecord(11, 2, "error-rate-after-deploy-rollback",
            "ROLLBACK", "strata-order-service-1", 0.8, true, null, 49,
            "{\"blastRadius\":25,\"reversibility\":20,\"diagnosisConfidence\":4}", "L2_APPROVAL",
            "AWAITING_APPROVAL", T0.plusSeconds(605));

    @Autowired
    private MockMvc mvc;

    @MockBean
    private IncidentRepository incidents;

    @MockBean
    private TrustService trust;

    @MockBean
    private AuditLogService audit;

    @BeforeEach
    void data() {
        when(incidents.findLatest(isNull(), anyInt())).thenReturn(List.of(UNKNOWN, BAD_DEPLOY, CRASH));
        when(incidents.findLatest(eq("OPEN"), anyInt())).thenReturn(List.of(BAD_DEPLOY));
        when(incidents.findDecisions(1)).thenReturn(List.of(RESTARTED));
        when(incidents.findDecisions(2)).thenReturn(List.of(ROLLBACK));
        when(incidents.findDecisions(3)).thenReturn(List.of());
        when(trust.all()).thenReturn(List.of(new TrustRecord(ActionType.RESTART, 4, 1, T0),
                TrustRecord.empty(ActionType.ROLLBACK)));
        when(audit.verify()).thenReturn(ChainVerification.ok(12, "a".repeat(64)));
    }

    @Test
    void overviewListsIncidentsNewestFirstWithTheirLatestDecision() throws Exception {
        mvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard/index"))
                .andExpect(content().string(containsString("service-down-restart → RESTART")))
                .andExpect(content().string(containsString("L0 auto")))
                .andExpect(content().string(containsString("15s")))
                .andExpect(content().string(containsString("2026-11-20 10:00:00 UTC")));
    }

    @Test
    void pendingApprovalShowsAPreviewAndTheButtons() throws Exception {
        String html = mvc.perform(get("/dashboard")).andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
                .contains("data-decision-id=\"11\"")
                .contains("data-verdict=\"approve\"").contains("data-verdict=\"deny\"")
                .contains("ROLLBACK").contains("L2 approval")
                .contains("error-rate-after-deploy-rollback (confidence 80%)")
                .contains("<strong>49</strong>")
                .contains("blastRadius 25").contains("reversibility 20")
                .contains("/dashboard/approvals.js");
        // largest factor first
        org.assertj.core.api.Assertions.assertThat(html.indexOf("blastRadius 25"))
                .isLessThan(html.indexOf("diagnosisConfidence 4"));
    }

    @Test
    void saysSoWhenNothingIsPending() throws Exception {
        when(incidents.findLatest(eq("OPEN"), anyInt())).thenReturn(List.of());

        mvc.perform(get("/dashboard"))
                .andExpect(content().string(containsString("Nothing is waiting for approval.")))
                .andExpect(content().string(not(containsString("data-decision-id"))));
    }

    @Test
    void showsTrustRecords() throws Exception {
        mvc.perform(get("/dashboard"))
                .andExpect(content().string(containsString("RESTART")))
                .andExpect(content().string(containsString("80%")));
    }

    @Test
    void showsTheAuditChainStatus() throws Exception {
        mvc.perform(get("/dashboard")).andExpect(content().string(containsString("Chain intact")))
                .andExpect(content().string(containsString("12 entries")));

        when(audit.verify()).thenReturn(ChainVerification.broken(12, 7, "hash mismatch", "b".repeat(64)));
        mvc.perform(get("/dashboard"))
                .andExpect(content().string(containsString("Chain broken")))
                .andExpect(content().string(containsString("First bad entry: #7. hash mismatch")));
    }

    @Test
    void filtersByStatus() throws Exception {
        mvc.perform(get("/dashboard").param("status", "open")).andExpect(status().isOk());
        verify(incidents, org.mockito.Mockito.atLeastOnce()).findLatest(eq("OPEN"), anyInt());

        mvc.perform(get("/dashboard").param("status", "MAYBE")).andExpect(status().isBadRequest());
    }

    @Test
    void incidentPageShowsDecisionsAndAuditEntries() throws Exception {
        when(incidents.findById(2)).thenReturn(Optional.of(BAD_DEPLOY));
        when(audit.forIncident(2)).thenReturn(List.of(
                new AuditEntry(20, T0.plusSeconds(600), AuditEventType.INCIDENT_OPENED, 2L,
                        "{\"alertName\":\"HighErrorRate\"}", "0".repeat(64), "c".repeat(64)),
                new AuditEntry(21, T0.plusSeconds(605), AuditEventType.DIAGNOSIS, 2L,
                        "{\"action\":\"ROLLBACK\"}", "c".repeat(64), "d".repeat(64))));

        mvc.perform(get("/dashboard/incidents/2"))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard/incident"))
                .andExpect(content().string(containsString("Incident #2: HighErrorRate on order-service")))
                .andExpect(content().string(containsString("AWAITING_APPROVAL")))
                .andExpect(content().string(containsString("blastRadius 25")))
                .andExpect(content().string(containsString("INCIDENT_OPENED")))
                .andExpect(content().string(containsString("dddddddddddd…")));
    }

    @Test
    void incidentPageShowsRejectedProposals() throws Exception {
        when(incidents.findById(3)).thenReturn(Optional.of(UNKNOWN));
        when(incidents.findDecisions(3)).thenReturn(List.of(new DecisionRecord(12, 3, null, "RESTART", "postgres",
                0.5, false, "target postgres is not on the allow-list", null, null, null, "REJECTED",
                T0.plusSeconds(900))));
        when(audit.forIncident(3)).thenReturn(List.of());

        mvc.perform(get("/dashboard/incidents/3"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("no: target postgres is not on the allow-list")))
                .andExpect(content().string(containsString("No audit entries.")));
    }

    @Test
    void unknownIncidentIs404() throws Exception {
        when(incidents.findById(any(Long.class))).thenReturn(Optional.empty());
        mvc.perform(get("/dashboard/incidents/99")).andExpect(status().isNotFound());
    }

    @Test
    void servesTheStylesheetAndScript() throws Exception {
        mvc.perform(get("/dashboard/dashboard.css")).andExpect(status().isOk());
        mvc.perform(get("/dashboard/approvals.js")).andExpect(status().isOk())
                .andExpect(content().string(containsString("/api/approvals/")));
    }
}
