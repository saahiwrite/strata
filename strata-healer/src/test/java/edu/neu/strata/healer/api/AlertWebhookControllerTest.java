package edu.neu.strata.healer.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import edu.neu.strata.healer.detection.AlertParser;
import edu.neu.strata.healer.detection.IncidentService;

@WebMvcTest(AlertWebhookController.class)
@Import({AlertParser.class, IncidentService.class})
class AlertWebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IncidentService incidentService;

    @Test
    void webhookReturns202AndOpensIncident() throws Exception {
        String body = """
                {"status":"firing","alerts":[{"status":"firing",
                "labels":{"alertname":"ServiceDown","service":"order-service","container":"order-service-1","severity":"critical"},
                "startsAt":"2026-11-06T10:00:00Z"}]}
                """;

        mockMvc.perform(post("/api/alerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted());

        assertEquals(1, incidentService.findAll().size());
    }
}
