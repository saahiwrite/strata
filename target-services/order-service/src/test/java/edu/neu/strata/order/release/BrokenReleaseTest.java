package edu.neu.strata.order.release;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BrokenReleaseTest {

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @AutoConfigureObservability
    class GoodRelease {

        @Autowired
        private MockMvc mvc;

        @Test
        void servesRequestsNormally() throws Exception {
            mvc.perform(get("/orders")).andExpect(status().isOk());
        }

        @Test
        void reportsTheRelease() throws Exception {
            mvc.perform(get("/actuator/prometheus"))
                    .andExpect(content().string(containsString("service_release{application=\"order-service\",broken=\"false\",version=\"dev\"} 1.0")));
        }
    }

    @Nested
    @SpringBootTest(properties = {"strata.release.version=1.1-broken", "strata.release.broken=true",
            "strata.release.failure-rate=1.0"})
    @AutoConfigureMockMvc
    @AutoConfigureObservability
    @ExtendWith(OutputCaptureExtension.class)
    class BrokenRelease {

        @Autowired
        private MockMvc mvc;

        @Test
        void failsBusinessRequestsWithTheSameBug(CapturedOutput output) throws Exception {
            mvc.perform(get("/orders")).andExpect(status().isInternalServerError());
            assertThat(output.getOut()).contains("Request failed in release 1.1-broken")
                    .contains("NullPointerException").contains("this.priceList");
        }

        @Test
        void healthAndChaosStayAvailable() throws Exception {
            mvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
            mvc.perform(get("/chaos")).andExpect(status().isOk());
        }

        @Test
        void chaosResetDoesNotFixIt() throws Exception {
            mvc.perform(post("/chaos/reset")).andExpect(status().isOk());
            mvc.perform(get("/orders")).andExpect(status().isInternalServerError());
        }

        @Test
        void reportsTheBrokenRelease() throws Exception {
            mvc.perform(get("/actuator/prometheus"))
                    .andExpect(content().string(containsString("broken=\"true\",version=\"1.1-broken\"")));
        }
    }

    @Test
    void defaultsAndValidation() {
        ReleaseProperties defaults = new ReleaseProperties(null, false, null);
        assertThat(defaults.version()).isEqualTo("dev");
        assertThat(defaults.failureRate()).isEqualTo(0.9);
        assertThatThrownBy(() -> new ReleaseProperties("1.1", true, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
