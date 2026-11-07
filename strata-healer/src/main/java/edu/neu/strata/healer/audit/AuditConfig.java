package edu.neu.strata.healer.audit;

import edu.neu.strata.healer.persistence.AuditLogRepository;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Spring beans for the audit log. The database comes from Spring Boot's usual
 * {@code spring.datasource.*} settings (or {@code SPRING_DATASOURCE_URL} and friends in
 * Docker Compose); Flyway creates the tables from {@code db/migration} at startup.
 */
@Configuration
public class AuditConfig {

    @Bean
    public AuditLogRepository auditLogRepository(JdbcClient jdbc) {
        return new AuditLogRepository(jdbc);
    }

    @Bean
    public AuditLogService auditLogService(AuditLogRepository repository, PlatformTransactionManager transactions) {
        return new AuditLogService(repository, new TransactionTemplate(transactions), Clock.systemUTC());
    }
}
