package edu.neu.strata.healer.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import edu.neu.strata.healer.detection.AlertParser;
import edu.neu.strata.healer.model.Alert;
import edu.neu.strata.healer.orchestration.HealingOrchestrator;

@WebMvcTest(AlertWebhookController.class)
@Import(AlertParser.class)
class AlertWebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private HealingOrchestrator orchestrator;

    @Test
    void webhookReturns202AndHandsAlertToOrchestrator() throws Exception {
        String body = """
                {"status":"firing","alerts":[{"status":"firing",
                "labels":{"alertname":"ServiceDown","service":"order-service","container":"order-service-1","severity":"critical"},
                "startsAt":"2026-11-06T10:00:00Z"}]}
                """;

        mockMvc.perform(post("/api/alerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted());

        verify(orchestrator).handle(any(Alert.class));
    }

    @Test
    void resolvedAlertsAreNotHandled() throws Exception {
        String body = """
                {"alerts":[{"status":"resolved","labels":{"alertname":"ServiceDown","service":"order-service"}}]}
                """;

        mockMvc.perform(post("/api/alerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted());

        verify(orchestrator, never()).handle(any(Alert.class));
    }
}
