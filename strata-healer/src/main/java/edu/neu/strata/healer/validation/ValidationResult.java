package edu.neu.strata.healer.validation;

/**
 * The {@link ActionValidator}'s verdict on one proposed action.
 *
 * @param allowed whether the action may go on to risk scoring
 * @param reason  why it was rejected (empty when allowed); safe to show and to put in the audit log
 */
public record ValidationResult(boolean allowed, String reason) {

    private static final ValidationResult ALLOWED = new ValidationResult(true, "");

    public ValidationResult {
        reason = reason == null ? "" : reason;
        if (!allowed && reason.isBlank()) {
            throw new IllegalArgumentException("a rejection needs a reason");
        }
    }

    public static ValidationResult allow() {
        return ALLOWED;
    }

    public static ValidationResult reject(String reason) {
        return new ValidationResult(false, reason);
    }
}
