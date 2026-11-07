package edu.neu.strata.inventory.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class InventoryControllerTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void listsSeededStock() throws Exception {
        mvc.perform(get("/inventory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[0].sku").value("SKU-1"))
                .andExpect(jsonPath("$[0].available").value(InventoryController.INITIAL_STOCK));
    }

    @Test
    void reservesStock() throws Exception {
        mvc.perform(post("/inventory/SKU-2/reserve").param("quantity", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(70));
        mvc.perform(get("/inventory/SKU-2")).andExpect(jsonPath("$.available").value(70));
    }

    @Test
    void rejectsReservationBeyondStock() throws Exception {
        mvc.perform(post("/inventory/SKU-3/reserve").param("quantity", "101"))
                .andExpect(status().isConflict());
        mvc.perform(get("/inventory/SKU-3")).andExpect(jsonPath("$.available").value(100));
    }

    @Test
    void rejectsInvalidRequests() throws Exception {
        mvc.perform(post("/inventory/SKU-1/reserve").param("quantity", "0")).andExpect(status().isBadRequest());
        mvc.perform(post("/inventory/NOPE/reserve")).andExpect(status().isNotFound());
        mvc.perform(get("/inventory/NOPE")).andExpect(status().isNotFound());
    }

    @Test
    void exposesPrometheusMetrics() throws Exception {
        mvc.perform(post("/inventory/SKU-1/reserve"));
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("inventory_reservations_total")))
                .andExpect(content().string(containsString("application=\"inventory-service\"")));
    }
}
