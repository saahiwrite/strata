package edu.neu.strata.healer.diagnosis;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

    /** The Ollama client; only created when the LLM is enabled. */
    @Bean
    @ConditionalOnProperty(name = "strata.llm.enabled", havingValue = "true")
    public LlmClient llmClient(RestClient.Builder builder,
                               @Value("${strata.llm.url:http://ollama:11434}") String llmUrl,
                               @Value("${strata.llm.model:qwen2.5:0.5b}") String model,
                               @Value("${strata.llm.timeout-seconds:60}") long timeoutSeconds) {
        log.info("LLM diagnosis enabled: {} at {}", model, llmUrl);
        return new OllamaClient(builder, llmUrl, model, Duration.ofSeconds(timeoutSeconds));
    }

    @Bean
    @Primary
    public DiagnosisChain diagnoser(RuleMatcher rules, Optional<LlmClient> llm,
                                    @Value("${strata.llm.max-confidence:0.6}") double maxConfidence) {
        List<Diagnoser> chain = new ArrayList<>();
        chain.add(rules);
        llm.ifPresentOrElse(
                client -> chain.add(new LlmDiagnoser(client, maxConfidence)),
                () -> log.info("Diagnosis: rules only (strata.llm.enabled is false)"));
        return new DiagnosisChain(chain);
    }
}
