package edu.neu.strata.healer.diagnosis;

import java.util.Optional;

/** Sends one prompt to a language model and returns its raw reply. Implemented by {@link OllamaClient}. */
public interface LlmClient {

    /**
     * @param system instructions the model must follow
     * @param user   the question, including the untrusted log lines as quoted data
     * @return the model's reply text, or empty if the model couldn't be reached or timed out
     */
    Optional<String> complete(String system, String user);

    /** The model's name, recorded with every LLM diagnosis. */
    String model();
}
