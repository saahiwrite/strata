package edu.neu.strata.chaos;

import edu.neu.strata.chaostest.TestServiceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = TestServiceApplication.class, properties = "strata.chaos.enabled=false")
@AutoConfigureMockMvc
class ChaosDisabledTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ApplicationContext context;

    @Test
    void noSwitchesWhenDisabled() throws Exception {
        assertThat(context.getBeansOfType(ChaosState.class)).isEmpty();
        assertThat(context.getBeansOfType(Terminator.class)).isEmpty();
        mvc.perform(post("/chaos/crash")).andExpect(status().isNotFound());
    }
}
