package edu.neu.strata.healer.validation;

import edu.neu.strata.healer.model.Diagnosis;
import edu.neu.strata.healer.policy.AllowList;
import edu.neu.strata.healer.policy.Policy;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Checks a proposed action against the policy's allow-list before it is scored or run (FR4).
 *
 * <p>Diagnoses come from log text, which is untrusted (and, from Phase 3, from an LLM that
 * reads it), so this is the gate that keeps STRATA to the actions and targets the team chose.
 * It rejects an action that isn't allow-listed, a target that isn't a plain container or
 * service name (so nothing like {@code "order-service; rm -rf /"} reaches Docker), and a
 * target the allow-list doesn't cover. Rejection reasons never echo more than a short, cleaned
 * piece of the target, so log text can't flood the audit log.
 */
@Component
public class ActionValidator {

    /** How much of a rejected target the reason quotes. */
    static final int QUOTED_TARGET_LENGTH = 64;

    private final AllowList allowList;

    public ActionValidator(Policy policy) {
        this.allowList = Objects.requireNonNull(policy, "policy").allowList();
    }

    public ValidationResult validate(Diagnosis diagnosis) {
        if (diagnosis == null) {
            return ValidationResult.reject("no diagnosis");
        }
        if (!allowList.allowsAction(diagnosis.proposedAction())) {
            return ValidationResult.reject("action " + diagnosis.proposedAction() + " is not on the allow-list");
        }
        String target = diagnosis.target();
        if (!AllowList.NAME.matcher(target).matches()) {
            return ValidationResult.reject("target '" + quote(target) + "' is not a valid container or service name");
        }
        if (!allowList.allowsTarget(target)) {
            return ValidationResult.reject("target '" + target + "' is not on the allow-list");
        }
        return ValidationResult.allow();
    }

    /** A short, printable version of untrusted text for a rejection reason. */
    static String quote(String text) {
        StringBuilder out = new StringBuilder();
        text.codePoints()
                .limit(QUOTED_TARGET_LENGTH)
                .forEach(c -> out.appendCodePoint(c >= 0x20 && c < 0x7f && c != '\'' ? c : '?'));
        return text.codePointCount(0, text.length()) > QUOTED_TARGET_LENGTH ? out + "..." : out.toString();
    }
}
