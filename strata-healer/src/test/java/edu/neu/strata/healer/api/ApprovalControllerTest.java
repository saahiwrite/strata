package edu.neu.strata.healer.api;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import edu.neu.strata.healer.orchestration.ApprovalService;
import edu.neu.strata.healer.orchestration.PendingApproval;

class ApprovalControllerTest {

    private final ApprovalService approvals = mock(ApprovalService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ApprovalController(approvals)).build();

    @Test
    void listsWhatIsWaiting() throws Exception {
        when(approvals.pending()).thenReturn(List.of(new PendingApproval(7, 3, "order-service", "rollback-rule",
                "ROLLBACK", "strata-order-service-1", 0.8, 40, "{\"blastRadius\":20}",
                "Replace strata-order-service-1 with its previous image, one instance first",
                Instant.parse("2026-11-08T10:00:00Z"), Instant.parse("2026-11-08T10:30:00Z"))));

        mvc.perform(get("/api/approvals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].decisionId").value(7))
                .andExpect(jsonPath("$[0].action").value("ROLLBACK"))
                .andExpect(jsonPath("$[0].riskFactors.blastRadius").value(20))
                .andExpect(jsonPath("$[0].preview").value(
                        "Replace strata-order-service-1 with its previous image, one instance first"));
    }

    @Test
    void approveIsAcceptedAndPassesOnWhoApproved() throws Exception {
        mvc.perform(post("/api/approvals/7/approve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"by\":\"madhuri\"}"))
                .andExpect(status().isAccepted());

        verify(approvals).approve(7, "madhuri");
    }

    @Test
    void approveWithoutABodyStillWorks() throws Exception {
        mvc.perform(post("/api/approvals/7/approve")).andExpect(status().isAccepted());

        verify(approvals).approve(7, "unknown");
    }

    @Test
    void denyAnswersOk() throws Exception {
        mvc.perform(post("/api/approvals/7/deny").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"by\":\"madhuri\"}"))
                .andExpect(status().isOk());

        verify(approvals).deny(7, "madhuri");
    }

    @Test
    void anUnknownDecisionIs404() throws Exception {
        doThrow(new NoSuchElementException("no decision 9")).when(approvals).approve(anyLong(), anyString());

        mvc.perform(post("/api/approvals/9/approve")).andExpect(status().isNotFound());
    }

    @Test
    void aDecisionThatIsAlreadyDecidedIs409() throws Exception {
        doThrow(new IllegalStateException("not waiting")).when(approvals).approve(anyLong(), anyString());
        doThrow(new IllegalStateException("not waiting")).when(approvals).deny(anyLong(), anyString());

        mvc.perform(post("/api/approvals/7/approve")).andExpect(status().isConflict());
        mvc.perform(post("/api/approvals/7/deny")).andExpect(status().isConflict());
    }
}
