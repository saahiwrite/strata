package edu.neu.strata.healer.diagnosis;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import edu.neu.strata.healer.model.Alert;
import edu.neu.strata.healer.model.Diagnosis;

@Component
public class RuleMatcher {

    private final List<Rule> rules;

    public RuleMatcher() {
        this(loadRules("rules.yaml"));
    }

    public RuleMatcher(List<Rule> rules) {
        this.rules = rules;
    }

    public Optional<Diagnosis> match(Alert alert, List<String> logLines) {
        for (Rule rule : rules) {
            if (matches(rule, alert, logLines)) {
                String target = alert.container() != null ? alert.container() : alert.service();
                return Optional.of(new Diagnosis(rule.id(), rule.action(), target, rule.confidence()));
            }
        }
        return Optional.empty();
    }

    private boolean matches(Rule rule, Alert alert, List<String> logLines) {
        if (!rule.alertName().equals(alert.alertName())) {
            return false;
        }
        if (rule.logContains().isEmpty()) {
            return true;
        }
        return logLines.stream().anyMatch(line -> rule.logContains().stream().anyMatch(line::contains));
    }

    private static List<Rule> loadRules(String file) {
        try (InputStream in = new ClassPathResource(file).getInputStream()) {
            RuleFile ruleFile = new ObjectMapper(new YAMLFactory()).readValue(in, RuleFile.class);
            return ruleFile.rules();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + file, e);
        }
    }

    private record RuleFile(List<Rule> rules) {
    }
}
