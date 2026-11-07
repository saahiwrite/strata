package edu.neu.strata.healer.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.neu.strata.healer.audit.AuditEventType;
import edu.neu.strata.healer.audit.AuditLogService;
import edu.neu.strata.healer.model.ActionType;
import edu.neu.strata.healer.persistence.AuditLogRepository;
import edu.neu.strata.healer.persistence.IncidentRepository;
import edu.neu.strata.healer.persistence.TestDatabase;
import edu.neu.strata.healer.persistence.TrustRepository;
import edu.neu.strata.healer.trust.TrustService;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The incidents, audit and trust endpoints, against a real (in-memory) database. */
class ApiTest {

    private static final Instant NOW = Instant.parse("2026-11-06T09:00:00Z");

    private final TestDatabase db = new TestDatabase();
    private final JdbcClient jdbc = JdbcClient.create(db.dataSource);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AuditLogService audit = new AuditLogService(new AuditLogRepository(jdbc), db.transactions, clock);
    private final TrustService trust = new TrustService(new TrustRepository(jdbc), db.transactions, audit, clock);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new IncidentController(new IncidentRepository(jdbc), audit),
            new AuditController(audit),
            new TrustController(trust))
            // Spring Boot's default: dates as ISO-8601 text, not numbers
            .setMessageConverters(new MappingJackson2HttpMessageConverter(Jackson2ObjectMapperBuilder.json()
                    .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build()))
            .build();

    private long incident(String alert, String status, int minutesAgo) {
        jdbc.sql("INSERT INTO incidents (alert_name, service, container, severity, status, opened_at) "
                        + "VALUES (?, 'order-service', 'strata-order-service-1', 'critical', ?, ?)")
                .params(alert, status, OffsetDateTime.ofInstant(NOW.minusSeconds(60L * minutesAgo), ZoneOffset.UTC))
                .update();
        return jdbc.sql("SELECT MAX(id) FROM incidents").query(Long.class).single();
    }

    @Test
    void listsIncidentsNewestFirst() throws Exception {
        incident("ServiceDown", "RESOLVED", 30);
        incident("HighErrorRate", "OPEN", 5);

        mvc.perform(get("/api/incidents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].alertName").value("HighErrorRate"))
                .andExpect(jsonPath("$[0].closedAt").value(nullValue()))
                .andExpect(jsonPath("$[1].openedAt").value("2026-11-06T08:30:00Z"));
        mvc.perform(get("/api/incidents").param("status", "resolved"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].alertName").value("ServiceDown"));
        mvc.perform(get("/api/incidents").param("limit", "1")).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/incidents").param("status", "MAYBE")).andExpect(status().isBadRequest());
    }

    @Test
    void showsOneIncidentWithItsDecisionsAndAuditEntries() throws Exception {
        long id = incident("ServiceDown", "OPEN", 1);
        jdbc.sql("INSERT INTO decisions (incident_id, rule_id, action, target, confidence, allowed, risk_score, "
                        + "risk_factors, autonomy_level, outcome, decided_at) VALUES (?, 'container-crash', 'RESTART', "
                        + "'strata-order-service-1', 0.95, TRUE, 9, '{\"blastRadius\":5,\"reversibility\":3}', "
                        + "'L0_AUTO', 'RECOVERED', ?)")
                .params(id, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)).update();
        audit.append(AuditEventType.INCIDENT_OPENED, id, Map.of("service", "order-service"));
        audit.append(AuditEventType.INCIDENT_OPENED, id + 1, Map.of());

        mvc.perform(get("/api/incidents/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incident.id").value(id))
                .andExpect(jsonPath("$.decisions", hasSize(1)))
                .andExpect(jsonPath("$.decisions[0].riskFactors.blastRadius").value(5))
                .andExpect(jsonPath("$.decisions[0].autonomyLevel").value("L0_AUTO"))
                .andExpect(jsonPath("$.decisions[0].outcome").value("RECOVERED"))
                .andExpect(jsonPath("$.audit", hasSize(1)))
                .andExpect(jsonPath("$.audit[0].eventType").value("INCIDENT_OPENED"));
        mvc.perform(get("/api/incidents/{id}", 999)).andExpect(status().isNotFound());
    }

    @Test
    void listsAndVerifiesTheAuditLog() throws Exception {
        audit.append(AuditEventType.INCIDENT_OPENED, 1L, Map.of());
        audit.append(AuditEventType.INCIDENT_OPENED, 2L, Map.of());
        audit.append(AuditEventType.INCIDENT_CLOSED, 1L, Map.of("status", "RESOLVED"));

        mvc.perform(get("/api/audit"))
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].seq").value(3));
        mvc.perform(get("/api/audit").param("limit", "2")).andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(get("/api/audit").param("incidentId", "1"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[1].eventType").value("INCIDENT_CLOSED"));
        mvc.perform(get("/api/audit/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.entries").value(3));
    }

    @Test
    void verifyReportsTampering() throws Exception {
        audit.append(AuditEventType.INCIDENT_OPENED, 1L, Map.of("service", "order-service"));
        audit.append(AuditEventType.INCIDENT_CLOSED, 1L, Map.of("status", "RESOLVED"));
        jdbc.sql("UPDATE audit_log SET payload = '{\"status\":\"ESCALATED\"}' WHERE seq = 2").update();

        mvc.perform(get("/api/audit/verify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.brokenAt").value(2));
    }

    @Test
    void listsTrustRecords() throws Exception {
        trust.recordSuccess(ActionType.RESTART, null);
        trust.recordFailure(ActionType.RESTART, null);

        mvc.perform(get("/api/trust"))
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].action").value("RESTART"))
                .andExpect(jsonPath("$[0].successes").value(1))
                .andExpect(jsonPath("$[0].failures").value(1))
                .andExpect(jsonPath("$[1].action").value("SCALE_OUT"))
                .andExpect(jsonPath("$[1].updatedAt").value(nullValue()));
    }
}
