package edu.neu.strata.healer.validation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Screens a service's log lines for text that tries to instruct the healer (prompt injection, FR4).
 * Logs are untrusted input: anyone who can make a service log a line can put words in front of the LLM.
 *
 * <p>The screen is a list of patterns, each with an id that ends up in the audit log. It errs on the
 * side of flagging: a false alarm only means the incident is diagnosed by the rules alone (or goes to a
 * human), while a miss is still caught later by the target lock, the allow-list and the policy's LLM floor.
 */
@Component
public class LogScreen {

    /** One kind of suspicious text. */
    record Signal(String id, Pattern pattern) {
    }

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    static final List<Signal> SIGNALS = List.of(
            // "ignore previous instructions", "disregard all rules", "forget your system prompt"
            new Signal("override-instructions", Pattern.compile(
                    "\\b(ignore|disregard|forget|override|bypass)\\b.{0,40}\\b(instructions?|prompts?|rules?|guidelines?|directions?|policy)\\b",
                    FLAGS)),
            // talking to a model: "you are now", "act as", "system prompt", "new instructions"
            new Signal("role-hijack", Pattern.compile(
                    "\\b(you are now|act as|pretend (to be|you are)|new instructions?|system prompt|as an ai|assistant:)",
                    FLAGS)),
            // dictating the reply: "reply with action ROLLBACK", "respond only with json", "{"action": ...}"
            new Signal("dictates-reply", Pattern.compile(
                    "\\b(reply|respond|answer|output|return)\\b.{0,30}\\b(with|only|json)\\b.{0,40}"
                            + "\\b(action|json|restart|rollback|scale_out|confidence)\\b"
                            + "|\"action\"\\s*:\\s*\"?(restart|rollback|scale_out|none)\\b",
                    FLAGS)),
            // shell and container commands: "docker rm -f postgres", "rm -rf /", "curl ... | sh", "drop table"
            new Signal("shell-command", Pattern.compile(
                    "\\b(docker|podman|kubectl)\\s+(rm|rmi|kill|stop|exec|run|system\\s+prune|compose\\s+down|delete)\\b"
                            + "|\\brm\\s+-[a-z]*[rf]|\\b(curl|wget)\\b[^|]{0,200}\\|\\s*(ba|z)?sh\\b|\\bsudo\\s+\\S"
                            + "|\\b(drop|truncate)\\s+(table|database|schema)\\b",
                    FLAGS)),
            // an order to an operator: "URGENT: restart the postgres container now", "please roll back the database"
            new Signal("operator-command", Pattern.compile(
                    "\\b(please|urgent|immediately|right now|you must|operator|healer|on-call)\\b.{0,60}"
                            + "\\b(restart|kill|stop|delete|remove|wipe|roll\\s*back|scale|shut\\s*down)\\b.{0,40}"
                            + "\\b(container|service|database|db|server|cluster|node)\\b",
                    FLAGS)));

    /** Longest excerpt of a withheld line kept in the audit log. */
    static final int EXCERPT_LENGTH = 160;

    /** A suspicious line and the signals it raised. */
    public record Finding(int line, Set<String> signals, String text) {

        /** The line on one line, without control characters, cut to {@link #EXCERPT_LENGTH}; safe to store. */
        public String excerpt() {
            String flat = text.replaceAll("\\p{Cntrl}", " ").strip();
            return flat.length() <= EXCERPT_LENGTH ? flat : flat.substring(0, EXCERPT_LENGTH) + "…";
        }
    }

    /**
     * The result of screening: the lines that may be shown to a diagnoser, and what was withheld.
     *
     * @param clean    the lines that raised no signal, in their original order
     * @param findings one entry per withheld line
     */
    public record Result(List<String> clean, List<Finding> findings) {

        public boolean suspicious() {
            return !findings.isEmpty();
        }

        /** Every signal raised, in the order first seen. */
        public Set<String> signals() {
            Set<String> all = new LinkedHashSet<>();
            findings.forEach(f -> all.addAll(f.signals()));
            return all;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The part of a log line a person would read. Our services log one JSON object per line
     * (Logback's LogstashEncoder), so for those only the message and stack trace are screened,
     * not field names such as {@code "service"} that appear on every line.
     */
    static String readableText(String line) {
        String trimmed = line.strip();
        if (trimmed.startsWith("{")) {
            try {
                JsonNode record = JSON.readTree(trimmed);
                if (record != null && record.has("message")) {
                    return (record.path("message").asText("") + " " + record.path("stack_trace").asText("")).strip();
                }
            } catch (Exception e) {
                // not JSON after all; screen the raw line
            }
        }
        return trimmed.replace("\\\"", "\"").replace("\\n", " ");
    }

    /** Splits the lines into clean ones and suspicious ones. Never throws; null lines are dropped. */
    public Result screen(List<String> lines) {
        List<String> clean = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        if (lines == null) {
            return new Result(List.of(), List.of());
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line == null) {
                continue;
            }
            Set<String> raised = signalsIn(line);
            if (raised.isEmpty()) {
                clean.add(line);
            } else {
                findings.add(new Finding(i, raised, line));
            }
        }
        return new Result(List.copyOf(clean), List.copyOf(findings));
    }

    static Set<String> signalsIn(String line) {
        String text = readableText(line);
        Set<String> raised = new LinkedHashSet<>();
        for (Signal signal : SIGNALS) {
            if (signal.pattern().matcher(text).find()) {
                raised.add(signal.id());
            }
        }
        return raised;
    }
}
