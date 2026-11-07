package edu.neu.strata.healer.persistence;

import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** A fresh in-memory H2 database (PostgreSQL mode) with the real Flyway migrations applied. */
public final class TestDatabase {

    public final DataSource dataSource;
    public final TransactionTemplate transactions;

    public TestDatabase() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        this.dataSource = ds;
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }
}
