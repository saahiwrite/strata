package edu.neu.strata.healer.policy;

import edu.neu.strata.healer.model.ActionType;
import edu.neu.strata.healer.model.AutonomyLevel;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Reads and checks {@code policy.yaml}. Any mistake (an unknown key, a weight total other
 * than 100, a value outside 0.0-1.0, an unknown action or level) fails with a
 * {@link PolicyException} that names the file and the field, so a bad policy never starts.
 */
public final class PolicyLoader {

    /** The policy bundled with the healer. */
    public static final String DEFAULT_RESOURCE = "policy.yaml";

    private static final Set<String> TOP_LEVEL_KEYS =
            Set.of("environment", "weights", "thresholds", "environments", "actions");
    private static final Set<String> THRESHOLD_KEYS = Set.of("l1From", "l2From", "l3From");
    private static final Set<String> ACTION_KEYS = Set.of("blastRadius", "reversibility", "minLevel");

    /** Loads the policy bundled on the classpath. */
    public Policy loadDefault() {
        return loadResource(DEFAULT_RESOURCE);
    }

    public Policy loadResource(String resource) {
        InputStream in = PolicyLoader.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            throw new PolicyException("policy " + resource + " not found on the classpath");
        }
        try (in) {
            return load(in, resource);
        } catch (IOException e) {
            throw new PolicyException("could not read policy " + resource, e);
        }
    }

    public Policy load(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return load(in, file.toString());
        } catch (IOException e) {
            throw new PolicyException("could not read policy " + file, e);
        }
    }

    /**
     * Parses a policy.
     *
     * @param source the file name to put in error messages
     */
    public Policy load(InputStream in, String source) {
        Object root;
        try {
            // SafeConstructor: plain maps, lists and scalars only, never arbitrary Java objects.
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            root = new Yaml(new SafeConstructor(options)).load(in);
        } catch (YAMLException e) {
            throw new PolicyException(source + " is not valid YAML: " + e.getMessage(), e);
        }
        try {
            return parse(root);
        } catch (IllegalArgumentException | ClassCastException e) {
            throw new PolicyException(source + ": " + e.getMessage(), e);
        }
    }

    private static Policy parse(Object root) {
        Map<String, Object> doc = map(root, "the policy");
        checkKeys(doc, TOP_LEVEL_KEYS, "the policy");

        String environment = text(doc.get("environment"), "environment");

        Map<String, Object> weightsYaml = map(require(doc, "weights", "the policy"), "weights");
        Map<RiskFactor, Integer> weights = new EnumMap<>(RiskFactor.class);
        for (Map.Entry<String, Object> entry : weightsYaml.entrySet()) {
            RiskFactor factor = RiskFactor.fromKey(entry.getKey());
            if (factor == null) {
                throw new IllegalArgumentException("unknown weight '" + entry.getKey() + "'");
            }
            weights.put(factor, integer(entry.getValue(), "weights." + entry.getKey()));
        }

        Map<String, Object> thresholdsYaml = map(require(doc, "thresholds", "the policy"), "thresholds");
        checkKeys(thresholdsYaml, THRESHOLD_KEYS, "thresholds");
        Thresholds thresholds = new Thresholds(
                integer(require(thresholdsYaml, "l1From", "thresholds"), "thresholds.l1From"),
                integer(require(thresholdsYaml, "l2From", "thresholds"), "thresholds.l2From"),
                integer(require(thresholdsYaml, "l3From", "thresholds"), "thresholds.l3From"));

        Map<String, Double> environments = new LinkedHashMap<>();
        if (doc.get("environments") != null) {
            for (Map.Entry<String, Object> entry : map(doc.get("environments"), "environments").entrySet()) {
                environments.put(entry.getKey(), number(entry.getValue(), "environments." + entry.getKey()));
            }
        }

        Map<ActionType, ActionPolicy> actions = new EnumMap<>(ActionType.class);
        if (doc.get("actions") != null) {
            for (Map.Entry<String, Object> entry : map(doc.get("actions"), "actions").entrySet()) {
                String name = entry.getKey();
                ActionType type = enumValue(ActionType.class, name, "actions");
                String where = "actions." + name;
                Map<String, Object> action = map(entry.getValue(), where);
                checkKeys(action, ACTION_KEYS, where);
                Object minLevel = action.get("minLevel");
                actions.put(type, new ActionPolicy(
                        number(require(action, "blastRadius", where), where + ".blastRadius"),
                        number(require(action, "reversibility", where), where + ".reversibility"),
                        minLevel == null
                                ? AutonomyLevel.L0_AUTO
                                : enumValue(AutonomyLevel.class, text(minLevel, where + ".minLevel"), where + ".minLevel")));
            }
        }

        return new Policy(environment, weights, thresholds, environments, actions);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value, String where) {
        if (!(value instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException(where + " must be a mapping");
        }
        for (Object key : m.keySet()) {
            if (!(key instanceof String)) {
                throw new IllegalArgumentException(where + " has a non-text key: " + key);
            }
        }
        return (Map<String, Object>) m;
    }

    private static Object require(Map<String, Object> map, String key, String where) {
        Object value = map.get(key);
        if (value == null) {
            throw new IllegalArgumentException(where + " is missing '" + key + "'");
        }
        return value;
    }

    private static void checkKeys(Map<String, Object> map, Set<String> allowed, String where) {
        for (String key : map.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("unknown key '" + key + "' in " + where);
            }
        }
    }

    private static String text(Object value, String field) {
        if (!(value instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank text");
        }
        return s.strip();
    }

    private static int integer(Object value, String field) {
        if (!(value instanceof Integer i)) {
            throw new IllegalArgumentException(field + " must be a whole number, was " + value);
        }
        return i;
    }

    private static double number(Object value, String field) {
        if (!(value instanceof Number n)) {
            throw new IllegalArgumentException(field + " must be a number, was " + value);
        }
        return n.doubleValue();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String name, String where) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown " + type.getSimpleName() + " '" + name + "' in " + where);
        }
    }
}
