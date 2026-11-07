package edu.neu.strata.order.chaos;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
class ChaosControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private Terminator terminator;

    @Autowired
    private MemoryLeak memoryLeak;

    @AfterEach
    void resetSwitches() throws Exception {
        mvc.perform(post("/chaos/reset"));
    }

    @Test
    void switchesAreOffByDefault() throws Exception {
        mvc.perform(get("/chaos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorRate").value(0.0))
                .andExpect(jsonPath("$.latencyMs").value(0));
        mvc.perform(get("/orders")).andExpect(status().isOk());
        verify(terminator, never()).terminate(1);
    }

    @Test
    void crashTerminatesWithExitCodeOne() throws Exception {
        mvc.perform(post("/chaos/crash"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("terminating"));
        verify(terminator).terminate(1);
    }

    @Test
    void errorSwitchFailsBusinessRequests() throws Exception {
        mvc.perform(post("/chaos/error").param("rate", "1.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorRate").value(1.0));

        mvc.perform(get("/orders"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.reason").value("chaos error switch"));

        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("chaos_injected_errors_total")))
                .andExpect(content().string(containsString("status=\"500\"")));
    }

    @Test
    void errorSwitchNeverBlocksChaosOrActuator() throws Exception {
        mvc.perform(post("/chaos/error").param("rate", "1.0"));

        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/chaos")).andExpect(status().isOk());
        mvc.perform(post("/chaos/reset")).andExpect(status().isOk());
        mvc.perform(get("/orders")).andExpect(status().isOk());
    }

    @Test
    void slowSwitchDelaysRequests() throws Exception {
        mvc.perform(post("/chaos/slow").param("delayMs", "300"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latencyMs").value(300));

        long start = System.nanoTime();
        mvc.perform(get("/orders")).andExpect(status().isOk());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        org.assertj.core.api.Assertions.assertThat(elapsedMs).isGreaterThanOrEqualTo(300);
    }

    @Test
    void resetTurnsEverythingOff() throws Exception {
        mvc.perform(post("/chaos/error").param("rate", "0.5"));
        mvc.perform(post("/chaos/slow").param("delayMs", "100"));

        mvc.perform(post("/chaos/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorRate").value(0.0))
                .andExpect(jsonPath("$.latencyMs").value(0));
    }

    @Test
    void memorySwitchRetainsHeapUntilReset() throws Exception {
        mvc.perform(post("/chaos/memory").param("mbPerSecond", "2").param("maxMb", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memory.active").value(true))
                .andExpect(jsonPath("$.memory.mbPerSecond").value(2));

        memoryLeak.tick();
        memoryLeak.tick();
        memoryLeak.tick();

        mvc.perform(get("/chaos")).andExpect(jsonPath("$.memory.leakedMb").value(3));
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(content().string(containsString("chaos_memory_leaked_bytes")));

        mvc.perform(post("/chaos/reset"))
                .andExpect(jsonPath("$.memory.active").value(false))
                .andExpect(jsonPath("$.memory.leakedMb").value(0));
    }

    @Test
    void rejectsInvalidValues() throws Exception {
        mvc.perform(post("/chaos/error").param("rate", "1.5")).andExpect(status().isBadRequest());
        mvc.perform(post("/chaos/error").param("rate", "-0.1")).andExpect(status().isBadRequest());
        mvc.perform(post("/chaos/error").param("rate", "abc")).andExpect(status().isBadRequest());
        mvc.perform(post("/chaos/slow").param("delayMs", "-1")).andExpect(status().isBadRequest());
        mvc.perform(post("/chaos/slow").param("delayMs", "600000")).andExpect(status().isBadRequest());
        mvc.perform(post("/chaos/memory").param("mbPerSecond", "0")).andExpect(status().isBadRequest());
        mvc.perform(post("/chaos/memory").param("maxMb", "-5")).andExpect(status().isBadRequest());
    }

    @Test
    void exposesSwitchGauges() throws Exception {
        mvc.perform(post("/chaos/slow").param("delayMs", "50"));
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(content().string(containsString("chaos_latency_ms{application=\"order-service\"} 50.0")))
                .andExpect(content().string(containsString("chaos_error_rate")));
    }
}
