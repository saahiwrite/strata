package edu.neu.strata.healer.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import edu.neu.strata.healer.model.ActionType;
import edu.neu.strata.healer.model.Alert;
import edu.neu.strata.healer.model.Diagnosis;
import edu.neu.strata.healer.model.DiagnosisSource;

class DiagnosisChainTest {

    private static Alert alert(String name) {
        return new Alert(name, "order-service", "strata-order-service-1", "critical", Instant.now());
    }

    private final Diagnoser llm = mock(Diagnoser.class);
    private final DiagnosisChain chain = new DiagnosisChain(List.of(new RuleMatcher(), llm));

    @Test
    void ruleMatchWinsAndTheLlmIsNeverAsked() {
        Optional<Diagnosis> found = chain.diagnose(alert("ServiceDown"), List.of());

        assertThat(found).get().extracting(Diagnosis::source).isEqualTo(DiagnosisSource.RULE);
        verify(llm, never()).diagnose(any(), any());
    }

    @Test
    void llmIsAskedOnlyWhenNoRuleMatches() {
        Diagnosis proposal = new Diagnosis("llm:m", ActionType.RESTART, "strata-order-service-1", 0.5,
                DiagnosisSource.LLM);
        when(llm.diagnose(any(), any())).thenReturn(Optional.of(proposal));

        assertThat(chain.diagnose(alert("HighErrorRate"), List.of("ERROR something new"))).contains(proposal);
    }

    @Test
    void emptyWhenNobodyKnows() {
        when(llm.diagnose(any(), any())).thenReturn(Optional.empty());

        assertThat(chain.diagnose(alert("HighErrorRate"), List.of())).isEmpty();
    }

    @Test
    void needsAtLeastOneDiagnoser() {
        assertThatThrownBy(() -> new DiagnosisChain(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void configAddsTheLlmOnlyWhenThereIsAClient() {
        DiagnosisConfig config = new DiagnosisConfig();
        RuleMatcher rules = new RuleMatcher();
        LlmClient client = config.llmClient(RestClient.builder(), "http://ollama:11434", "m", 5);

        DiagnosisChain off = config.diagnoser(rules, Optional.empty(), 0.6);
        DiagnosisChain on = config.diagnoser(rules, Optional.of(client), 0.6);

        assertThat(off.diagnosers()).containsExactly(rules);
        assertThat(on.diagnosers()).hasSize(2).first().isSameAs(rules);
        assertThat(on.diagnosers().get(1)).isInstanceOf(LlmDiagnoser.class);
        assertThat(client.model()).isEqualTo("m");
    }

    @Test
    void withoutLlmKeepsTheRulesAndDropsTheLlm() {
        Diagnoser llmThatWouldAnswer = new LlmDiagnoser(new LlmClient() {
            @Override
            public Optional<String> complete(String system, String user) {
                return Optional.of("{\"action\": \"RESTART\", \"confidence\": 0.5}");
            }

            @Override
            public String model() {
                return "m";
            }
        }, 0.6);
        DiagnosisChain full = new DiagnosisChain(List.of(new RuleMatcher(), llmThatWouldAnswer));

        assertThat(full.diagnose(alert("HighErrorRate"), List.of())).isPresent();
        assertThat(full.withoutLlm().diagnose(alert("HighErrorRate"), List.of())).isEmpty();
        assertThat(full.withoutLlm().diagnose(alert("ServiceDown"), List.of())).isPresent();
    }
}
