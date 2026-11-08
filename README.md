# STRATA: Self-healing with Trust and Risk-Adaptive Autonomy

When a backend service crashes, slows down or gets overloaded, an on-call engineer usually has to read the logs, find the cause and fix it by hand. That's slow, and someone has to stay awake for it. Fixing everything automatically is risky too: a wrong restart or rollback can make an outage worse, and an AI that reads logs can be tricked by text an attacker plants in them. **STRATA** is a Java self-healing backend for on-call engineers. It detects failures from observability data (Prometheus, Loki, Alertmanager), diagnoses them (rules first, a local LLM only for unknown errors), checks each proposed fix against an allow-list and scores its risk. It then runs the fix automatically, asks a human to approve it, or hands it to a human, and it raises or lowers each action's autonomy based on how well past fixes worked. Every decision goes into a tamper-evident audit log.

**Research question:** can a backend safely raise or lower its autonomy based on the measured success and risk of past actions?

**Primary use case (a service crashes):** a service instance crashes → Prometheus detects the failed health check and Alertmanager alerts STRATA → STRATA reads recent logs, matches a rule and proposes a restart → the risk score is low (one instance, easy to undo), so it restarts the service automatically → STRATA checks the metrics, confirms recovery, logs the incident and updates the restart action's success record.

**Secondary use case (a bad deployment):** a rollback scores higher risk, so the engineer must approve it on the dashboard first.

## Team

| Member | GitHub Username | Role |
|---|---|---|
| Nida Esen | [@NidaEsenn](https://github.com/NidaEsenn) | Target backend: order and inventory services, failure switches, metrics, JSON logs, Nginx, failure scenarios |
| Shivani Radhakrishnan | [@Shivani-Radhakrishnan](https://github.com/Shivani-Radhakrishnan) | Observability and infrastructure: Docker Compose, Prometheus, Alertmanager, Loki, Grafana, recovery verification, CI |
| Madhuri Hegde | [@madhurihegde2](https://github.com/madhurihegde2) | Healer pipeline: alert webhook, detection, rule-based diagnosis, Docker action execution, orchestration |
| Saahithi Mallarapu | [@saahiwrite](https://github.com/saahiwrite) | Risk, policy, trust and audit: risk scorer, YAML policy, action validator, trust records, hash-chained audit log, persistence |

## Repository Structure

The folders follow the components in our Phase 1 system design.

```
strata/
  target-services/
    order-service/        Spring Boot service that STRATA heals (metrics, JSON logs, /chaos/* failure switches)
    inventory-service/    Second Spring Boot service, behind Nginx with order-service
  strata-healer/          The STRATA healer (Spring Boot, Java 21)
    src/main/java/edu/neu/strata/healer/
      model/              Shared types between the stages: Alert, Diagnosis, ActionType, AutonomyLevel, RiskAssessment, ExecutionResult
      api/                REST endpoints: Alertmanager webhook, incidents and audit API
      detection/          Turns incoming alerts into incidents
      diagnosis/          Rule matcher (rules.yaml) and Loki log client; LLM diagnosis later
      validation/         Action allow-list; rejects unknown actions and targets
      policy/             YAML policy loader and risk scorer (score 0-100 → L0-L3)
      execution/          Docker actions (restart, later scale out and rollback) and cooldown guard
      verification/       Prometheus client and recovery verifier
      trust/              Per-action success and failure record (earned autonomy)
      audit/              Tamper-evident, hash-chained audit log
      orchestration/      Healing loop: detect → diagnose → validate → score → execute → verify → audit
      persistence/        Database entities and repositories
    src/main/resources/db/migration/   Flyway migrations (incidents, decisions, audit_log, action_trust)
    src/test/java/edu/neu/strata/healer/   Unit tests (JUnit 5) and integration tests (Testcontainers)
  infra/
    nginx/                Load balancer config
    prometheus/           Scrape config and alert rules
    alertmanager/         Routes alerts to the healer's webhook
    loki/  promtail/      Log storage and log shipping
    grafana/provisioning/ Data sources and dashboards (error rate, latency, memory)
  scenarios/              Catalogue of the 15-20 injected failures and the inject.sh script
  docs/                   Phase 1 design document (phase1-design.md) and other project docs
  .github/workflows/      CI (mvn verify) (planned, not in the repository yet)
  .env.example            Example environment variables (copy to .env)
  pom.xml                 Parent pom (Spring Boot 3.3, Java 21); lists every Maven module
```

## Setup and Run

> The system was built during Phase 2 (Nov 3-7, 2026). On `main` today, only the target services can be built and tested (`mvn verify` inside each service folder). The commands below are how everything will run once the open pull requests (Compose file, parent pom, healer module) are merged. We'll update this section if any step changes.

**Prerequisites:**
- Java 21
- Maven 3.9+
- Docker Desktop (or Docker Engine) with Docker Compose v2
- Git

No accounts or API keys are needed. The LLM diagnosis planned for Phase 3 will use a local Ollama model.

```bash
# Clone
git clone https://github.com/saahiwrite/strata.git
cd strata

# Configure environment (edit values as needed; never commit .env)
cp .env.example .env

# Build all modules
mvn clean package -DskipTests

# Run everything: target services, Nginx, PostgreSQL, Prometheus, Alertmanager,
# Loki, Promtail, Grafana and the STRATA healer
docker compose up --build

# Run tests (unit tests; integration tests need Docker running)
mvn verify
```

**Trying the primary use case (once Phase 2 is complete):**

```bash
# Crash order-service through its failure switch
./scenarios/inject.sh crash order-service

# Watch STRATA detect, restart and verify it, then check the incident and audit log
curl http://localhost:9000/api/incidents
curl http://localhost:9000/api/audit
curl http://localhost:9000/api/audit/verify
```

**Environment variables** (`.env.example`):

| Variable | Purpose |
|---|---|
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | Database for incidents, decisions, trust records and the audit log |
| `HEALER_PORT` | Port of the STRATA healer (default 9000) |
| `POSTGRES_PORT`, `PROMETHEUS_PORT`, `ALERTMANAGER_PORT`, `LOKI_PORT`, `GRAFANA_PORT` | Optional host ports (5432, 9090, 9093, 3100, 3000). Change only if a port is taken |
| `GRAFANA_ADMIN_PASSWORD` | Grafana admin password (default `admin`). Dashboards can be viewed without logging in |
| `DOCKER_HOST` | Only if docker-java can't find the Docker socket (some macOS/Windows setups) |

## Progress So Far

| Functional Requirement (from Phase 1) | Status | Notes |
|---|---|---|
| FR1: Services expose metrics, JSON logs and failure switches (error, slow, crash, memory growth) | Done | order-service and inventory-service (both on :8080 in their containers) behind Nginx (:8080 on the host), `/actuator/prometheus`, JSON logs on stdout, `/chaos/crash\|error\|slow\|memory\|reset`; 18-scenario catalogue and `scenarios/inject.sh` (#27, merged) |
| FR2: Alert on defined thresholds and open an incident | In review | Compose with PostgreSQL and Prometheus (#23); `ServiceDown` and `HighErrorRate` alert rules with promtool tests and the Alertmanager webhook (#24); healer skeleton and `POST /api/alerts` (#19); `AlertParser` and `IncidentService` (#28) |
| FR3: Diagnose with rules first; use the LLM when no rule matches | In review | Phase 2: `RuleMatcher` with `rules.yaml` and `LokiLogClient` (#29); Loki and Promtail (#25). Phase 3: LLM (Ollama) |
| FR4: Treat logs as untrusted and run only allow-listed actions | In review | `allowList` in `policy.yaml` (actions and targets) and `ActionValidator`, which also rejects targets that aren't plain container names (#12). Phase 3: prompt-injection defense |
| FR5: Score each action by blast radius, reversibility, environment, diagnosis confidence and past failures | In review | `policy.yaml` (weights, environments, per-action risk), `PolicyLoader`, `RiskScorer` (#8) |
| FR6: Map the score to L0 auto, L1 auto with undo, L2 approval, L3 human only | In review | Thresholds in `policy.yaml`; an action can have a `minLevel` (rollback always needs approval) (#8) |
| FR7: Support restart, scale out and rollback | In review | Phase 2: restart through docker-java (`DockerActionExecutor`) and a 60 s `CooldownGuard` (#29). Phase 3: scale out and rollback |
| FR8: For L2, show a preview, wait for approval, fall back to a safe action on timeout | Not started | Phase 3 |
| FR9: Verify recovery; roll back and escalate if the service did not recover | In review | Phase 2: `PrometheusClient` and `PrometheusRecoveryVerifier` (#26), Grafana dashboards (#25). Phase 3: rollback and escalation |
| FR10: Promote or demote each action's level from its success record | In review | `TrustService` keeps each action's successes and failures (`action_trust`), writes each change to the audit log and gives the risk scorer its past-failures input; `GET /api/trust` (#14). Phase 3: earned autonomy |
| FR11: Write every decision to a tamper-evident audit log | In review | SHA-256 hash chain (`HashChain`), `AuditLogService` (append, verify) and the Flyway schema for incidents, decisions, trust records and the audit log (#12). The orchestrator writes every stage of an incident to it (#33). `GET /api/audit` and `GET /api/audit/verify` (#14) |
| FR12: Show incidents, approvals and action levels on a dashboard | In review | Phase 2: JSON API: `GET /api/incidents` (newest first, `?status=`), `GET /api/incidents/{id}` (with its decisions and audit entries), `GET /api/trust` (#14). Phase 3: Thymeleaf dashboard |

**Working end to end:** not yet. On `main`, the target services, Nginx, the failure switches and the scenario catalogue work (#27). The `HealingOrchestrator` (#33) wires the healer stages together (detect → diagnose → validate → score → execute → verify → audit → trust). Its tests run a crash through the real rule matcher, validator, risk scorer, audit log, trust service and database schema, with Docker, Loki and the recovery check replaced by mocks, and a crashed container heals with no human input. It has not been run against the real Compose stack yet. What's still missing for that is the healer's Compose service with its database settings, `CrashRecoveryIT` and CI. The goal is that `docker compose up` starts everything and a crashed service is detected, restarted and verified with no human input.

**Merge order** (each stacked PR moves to `main` once the one below it is merged): #20 (parent pom and shared types) → #21 → #22 → #30 (policy, validator, audit, trust) and #26 (recovery verifier); #19 → #28 → #29 (healer pipeline); #23 → #24 → #25 (infrastructure).

## Testing So Far

- **In review (unit tests, JUnit 5, run by `mvn verify`):** the shared types, `PolicyLoaderTest`, `RiskScorerTest`, `PolicyConfigTest` (#8), and `AllowListTest`, `ActionValidatorTest`, `HashChainTest`, `AuditLogServiceTest` (tampering, deleted entries, forks, 40 concurrent appends), `SchemaMigrationTest` and `AuditConfigTest` (#12), and `TrustRecordTest`, `TrustServiceTest` (40 concurrent updates, audit entries, failures raising the risk score) and `ApiTest` (the incidents, audit and trust endpoints, including a tampered log) (#14). The persistence tests run the real Flyway migrations on in-memory H2 in PostgreSQL mode; `CrashRecoveryIT` will cover real PostgreSQL.
- **Target services (on `main`):** 23 unit and MockMvc tests in each of `order-service` and `inventory-service` (API, Prometheus metrics, JSON logging, every chaos switch and its input validation), including `ChaosControllerTest`. Run with `mvn verify` inside each service folder (#27).
- **Healer pipeline (on `main`):** `AlertWebhookControllerTest` (#19), `AlertParserTest`, `IncidentServiceTest` (#28), `RuleMatcherTest`, `CooldownGuardTest`, `DockerActionExecutorTest`, `LokiLogClientTest` (#29), and `HealingOrchestratorTest` (#33: a crash healing end to end, escalation when the action fails or the service doesn't recover, an unknown alert, a rejected target, the cooldown, and an action that waits for approval). `IncidentServiceTest` runs against the real Flyway schema on H2. Run `mvn verify` in the repository root: the `strata-healer` module currently runs 163 tests (all of the healer tests above plus the policy, audit, trust and verifier ones) and they all pass.
- **Observability (in review):** promtool unit tests for the alert rules (#24); `PrometheusClientTest`, `PrometheusRecoveryVerifierTest`, `VerificationConfigTest` (#26).
- **Still planned:** the integration test `CrashRecoveryIT` (Testcontainers), and a GitHub Actions workflow that runs `mvn verify` on every push.

## Next Steps (Phase 3)

- LLM diagnosis of unknown errors with a local Ollama model, plus prompt-injection defense for log text
- Scale-out and rollback actions, with canary execution (one instance first)
- Approval dashboard (Thymeleaf) for L2 actions, with a preview, Approve/Deny and a safe fallback on timeout
- Earned autonomy: promote or demote each action's level from its trust record
- Benchmark of 15-20 injected failures (crash, slow responses, traffic spike, bad deployment, memory growth, malicious log lines), each run three ways: manual fix, rules only, full STRATA
- Measure MTTR, auto-resolution rate, false-fix rate, approvals per incident and injection block rate

## Team Contributions

> Each member updates their own row with exactly what they built this phase.

| Member | Contributions This Phase |
|---|---|
| Nida Esen | Built `order-service` and `inventory-service` (Spring Boot 3, Actuator, Prometheus metrics, JSON logs via Logback); the failure switches (crash, error rate, slow responses, memory growth, reset) with their metrics; the Nginx entry point on :8080; the 18-scenario failure catalogue and `scenarios/inject.sh`; `ChaosControllerTest`, `ChaosStateTest`, `MemoryLeakTest`, `OrderControllerTest`, `InventoryControllerTest`, `JsonLoggingTest` |
| Shivani Radhakrishnan | Docker Compose file with PostgreSQL, Prometheus, Alertmanager, Loki, Promtail and Grafana; Prometheus scrape config; `ServiceDown` and `HighErrorRate` alert rules with promtool tests; Alertmanager webhook to the healer; Grafana dashboard (up/down, error rate, latency, memory, logs); `PrometheusClient` and `PrometheusRecoveryVerifier` with unit tests (#23-#26) |
| Madhuri Hegde | Built the healer pipeline in `strata-healer`.<br>**Skeleton and webhook (#19):** the Spring Boot application, its package layout and `POST /api/alerts`, which accepts Alertmanager JSON and returns 202.<br>**Detection (#28):** `AlertParser`, which turns the webhook payload into alerts and skips resolved or incomplete ones, and `IncidentService`, which opens, resolves and escalates incidents and reuses the open incident when Alertmanager repeats an alert.<br>**Diagnosis and execution (#29):** `RuleMatcher` with `rules.yaml` (a crash maps to a restart, with a more specific rule when the logs show `OutOfMemoryError`); `LokiLogClient`; `DockerActionExecutor`, which restarts a named container through docker-java; and `CooldownGuard`.<br>**Orchestration (#33):** `HealingOrchestrator`, which runs an alert through diagnosis, validation, risk scoring, execution, recovery verification, the audit log and the trust record, and handles L2 (waits for approval), L3 (hands to a human) and failures (escalates). It saves incidents and decisions to the database (`IncidentStore`, `DecisionStore`), and the `V2` migration fixes the `decisions.outcome` column, which was too short for `AWAITING_APPROVAL`.<br>**Tests:** `AlertWebhookControllerTest`, `AlertParserTest`, `IncidentServiceTest`, `RuleMatcherTest`, `CooldownGuardTest`, `DockerActionExecutorTest`, `LokiLogClientTest` and `HealingOrchestratorTest` (10 cases). |
| Saahithi Mallarapu | Set up the repository (folder scaffold, `.env.example`, `.gitignore`, labels, the Phase 2 milestone and issues), and wrote this README and the Phase 1 design doc (`docs/phase1-design.md`).<br>**Shared foundation (#20):** the parent `pom.xml`, and the types every healing stage uses: `Alert`, `Diagnosis`, `ActionType`, `AutonomyLevel`, `RiskAssessment`, `ExecutionResult`, `ActionExecutor` and `RecoveryVerifier`.<br>**Risk policy (#21):** `policy.yaml` (factor weights, level thresholds, environments, per-action risk); `PolicyLoader`, which reads it safely and stops startup on a bad policy; and `RiskScorer`, which turns blast radius, reversibility, environment, diagnosis confidence and past failures into a score of 0-100 and a level from L0 to L3.<br>**Validation and audit (#22):** the action allow-list and `ActionValidator`, which rejects unlisted actions and target names injected through logs; the Flyway schema (`incidents`, `decisions`, `action_trust`, `audit_log`); and the SHA-256 hash-chained audit log (`HashChain`, `AuditLogService`), which detects changed, deleted, reordered or forged entries.<br>**Trust and API (#30):** `TrustService`, which keeps each action's successes and failures and feeds them into the risk score; and the read-only endpoints `GET /api/incidents`, `/api/incidents/{id}`, `/api/audit`, `/api/audit/verify` and `/api/trust`.<br>**Tests:** 105 JUnit 5 tests in the healer, run by `mvn verify`, including `PolicyLoaderTest`, `RiskScorerTest`, `ActionValidatorTest`, `HashChainTest`, `AuditLogServiceTest` (tampering and 40 concurrent appends), `SchemaMigrationTest`, `TrustServiceTest` and `ApiTest`.<br>**End of Phase 2 (#31):** brought the README's progress, testing and structure sections up to date. |
