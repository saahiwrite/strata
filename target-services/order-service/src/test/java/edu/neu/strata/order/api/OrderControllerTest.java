package edu.neu.strata.order.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
class OrderControllerTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void createsAndFetchesOrder() throws Exception {
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-1\",\"quantity\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value("SKU-1"))
                .andExpect(jsonPath("$.quantity").value(2));

        mvc.perform(get("/orders/1")).andExpect(status().isOk());
    }

    @Test
    void rejectsInvalidOrder() throws Exception {
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"\",\"quantity\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownOrderIs404() throws Exception {
        mvc.perform(get("/orders/999")).andExpect(status().isNotFound());
    }

    @Test
    void exposesPrometheusMetrics() throws Exception {
        mvc.perform(get("/orders"));
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("http_server_requests_seconds")))
                .andExpect(content().string(containsString("orders_placed_total")))
                .andExpect(content().string(containsString("application=\"order-service\"")));
    }

    @Test
    void healthIsUp() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
