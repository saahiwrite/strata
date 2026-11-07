package edu.neu.strata.healer.policy;

/** The policy file is missing, isn't valid YAML, or breaks a policy rule. */
public class PolicyException extends RuntimeException {

    public PolicyException(String message) {
        super(message);
    }

    public PolicyException(String message, Throwable cause) {
        super(message, cause);
    }
}
