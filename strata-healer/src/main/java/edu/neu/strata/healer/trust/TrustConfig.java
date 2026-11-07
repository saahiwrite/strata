package edu.neu.strata.healer.trust;

import edu.neu.strata.healer.audit.AuditLogService;
import edu.neu.strata.healer.persistence.TrustRepository;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Spring beans for the trust records. Needs the audit log's beans ({@code AuditConfig}). */
@Configuration
public class TrustConfig {

    @Bean
    public TrustRepository trustRepository(JdbcClient jdbc) {
        return new TrustRepository(jdbc);
    }

    @Bean
    public TrustService trustService(TrustRepository repository, PlatformTransactionManager transactions,
                                     AuditLogService audit) {
        return new TrustService(repository, new TransactionTemplate(transactions), audit, Clock.systemUTC());
    }
}
