package edu.neu.strata.order;

import edu.neu.strata.chaos.Terminator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The switches themselves are tested in chaos-kit; this checks they are wired into this service. */
@SpringBootTest
@AutoConfigureMockMvc
class ChaosWiringTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private Terminator terminator;

    @AfterEach
    void reset() throws Exception {
        mvc.perform(post("/chaos/reset"));
    }

    @Test
    void errorSwitchFailsThisServicesEndpoints() throws Exception {
        mvc.perform(post("/chaos/error").param("rate", "1.0")).andExpect(status().isOk());
        mvc.perform(get("/orders")).andExpect(status().isInternalServerError());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void crashSwitchIsAvailable() throws Exception {
        mvc.perform(post("/chaos/crash")).andExpect(status().isOk());
        verify(terminator).terminate(1);
    }
}
