package edu.neu.strata.healer.policy;

import edu.neu.strata.healer.model.ActionType;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What STRATA may ever do (FR4): the actions it may run and the containers or services it may
 * run them on. Anything not listed is rejected by the {@code ActionValidator}, whatever the
 * diagnosis (or, later, the LLM) proposes. An empty allow-list allows nothing.
 *
 * <p>A target pattern is an exact name, or a name ending in {@code *} that matches every name
 * starting with what comes before it ({@code strata-order-service-*} matches
 * {@code strata-order-service-1}). Patterns may only use the characters a Docker container name
 * can have, so the list can't hide a regular expression or shell text.
 *
 * @param actions the action types that may run
 * @param targets the target patterns actions may run on
 */
public record AllowList(Set<ActionType> actions, List<String> targets) {

    /** Allows nothing. Used when the policy has no {@code allowList} section. */
    public static final AllowList NONE = new AllowList(Set.of(), List.of());

    /** A Docker container or Compose service name: starts with a letter or digit, at most 128 characters. */
    public static final Pattern NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}");

    public AllowList {
        actions = actions == null || actions.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(actions));
        targets = targets == null ? List.of() : List.copyOf(targets);
        for (String target : targets) {
            Objects.requireNonNull(target, "allowList.targets must not contain empty entries");
            String name = target.endsWith("*") ? target.substring(0, target.length() - 1) : target;
            if (!NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("allowList.targets entry '" + target
                        + "' must be a container or service name, optionally ending in *");
            }
        }
    }

    public boolean allowsAction(ActionType type) {
        return type != null && actions.contains(type);
    }

    /** True when {@code target} is a valid name and matches one of the patterns. */
    public boolean allowsTarget(String target) {
        if (target == null || !NAME.matcher(target).matches()) {
            return false;
        }
        for (String pattern : targets) {
            boolean matches = pattern.endsWith("*")
                    ? target.startsWith(pattern.substring(0, pattern.length() - 1))
                    : target.equals(pattern);
            if (matches) {
                return true;
            }
        }
        return false;
    }
}
