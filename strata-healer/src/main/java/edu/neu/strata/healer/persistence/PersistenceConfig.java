package edu.neu.strata.healer.persistence;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Repositories that don't belong to one service (the audit and trust ones live in their own configs). */
@Configuration
public class PersistenceConfig {

    @Bean
    public IncidentRepository incidentRepository(JdbcClient jdbc) {
        return new IncidentRepository(jdbc);
    }
}
