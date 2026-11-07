package edu.neu.strata.healer.audit;

import static org.assertj.core.api.Assertions.assertThat;

import edu.neu.strata.healer.model.ActionType;
import edu.neu.strata.healer.model.Diagnosis;
import edu.neu.strata.healer.persistence.IncidentRepository;
import edu.neu.strata.healer.persistence.PersistenceConfig;
import edu.neu.strata.healer.policy.PolicyConfig;
import edu.neu.strata.healer.trust.TrustConfig;
import edu.neu.strata.healer.trust.TrustService;
import edu.neu.strata.healer.validation.ActionValidator;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcClientAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The beans wire up the way the healer will start: Spring Boot's DataSource, Flyway at startup, then the audit log. */
class AuditConfigTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    FlywayAutoConfiguration.class,
                    JdbcTemplateAutoConfiguration.class,
                    JdbcClientAutoConfiguration.class,
                    DataSourceTransactionManagerAutoConfiguration.class))
            .withPropertyValues("spring.datasource.url=jdbc:h2:mem:audit-config;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")
            .withUserConfiguration(AuditConfig.class, TrustConfig.class, PersistenceConfig.class, PolicyConfig.class,
                    ActionValidator.class);

    @Test
    void flywayMigratesAndTheAuditLogWorks() {
        context.run(ctx -> {
            AuditLogService audit = ctx.getBean(AuditLogService.class);
            audit.append(AuditEventType.INCIDENT_OPENED, 1L, Map.of("service", "order-service"));

            assertThat(audit.verify().valid()).isTrue();
            assertThat(audit.verify().entries()).isEqualTo(1);
        });
    }

    @Test
    void trustRecordsAndIncidentsAreWired() {
        context.run(ctx -> {
            TrustService trust = ctx.getBean(TrustService.class);
            trust.recordSuccess(ActionType.RESTART, null);

            assertThat(trust.get(ActionType.RESTART).successes()).isEqualTo(1);
            assertThat(ctx.getBean(IncidentRepository.class).findLatest(null, 10)).isEmpty();
        });
    }

    @Test
    void validatorUsesTheBundledAllowList() {
        context.run(ctx -> assertThat(ctx.getBean(ActionValidator.class)
                .validate(new Diagnosis("container-crash", ActionType.RESTART, "order-service", 0.95))
                .allowed()).isTrue());
    }
}
