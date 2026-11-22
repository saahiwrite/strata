package edu.neu.strata.healer.diagnosis;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

/**
 * Builds the healer's diagnoser: the rules first, then (when {@code strata.llm.enabled}) the local LLM.
 * With the LLM off, an alert no rule matches is escalated to a human, as in Phase 2.
 */
@Configuration
public class DiagnosisConfig {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisConfig.class);

    @Bean
    @Primary
    public DiagnosisChain diagnoser(RuleMatcher rules, RestClient.Builder builder,
                                    @Value("${strata.llm.enabled:false}") boolean llmEnabled,
                                    @Value("${strata.llm.url:http://ollama:11434}") String llmUrl,
                                    @Value("${strata.llm.model:qwen2.5:0.5b}") String model,
                                    @Value("${strata.llm.timeout-seconds:60}") long timeoutSeconds,
                                    @Value("${strata.llm.max-confidence:0.6}") double maxConfidence) {
        List<Diagnoser> chain = new ArrayList<>();
        chain.add(rules);
        if (llmEnabled) {
            LlmClient client = new OllamaClient(builder, llmUrl, model, Duration.ofSeconds(timeoutSeconds));
            chain.add(new LlmDiagnoser(client, maxConfidence));
            log.info("Diagnosis: rules, then the LLM {} at {}", model, llmUrl);
        } else {
            log.info("Diagnosis: rules only (strata.llm.enabled is false)");
        }
        return new DiagnosisChain(chain);
    }
}
