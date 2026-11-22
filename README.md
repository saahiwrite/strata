# STRATA: Self-healing with Trust and Risk-Adaptive Autonomy

When a backend service crashes, slows down or gets overloaded, an on-call engineer usually has to read the logs, find the cause and fix it by hand. That's slow, and someone has to stay awake for it. Fixing everything automatically is risky too: a wrong restart or rollback can make an outage worse, and an AI that reads logs can be tricked by text an attacker plants in them. **STRATA** is a Java self-healing backend for on-call engineers. It detects failures from observability data (Prometheus, Loki, Alertmanager), diagnoses them (rules first, a local LLM only for unknown errors), checks each proposed fix against an allow-list and scores its risk. It then runs the fix automatically, asks a human to approve it, or hands it to a human, and it raises or lowers each action's autonomy based on how well past fixes worked. Every decision goes into a tamper-evident audit log.

**Research question:** can a backend safely raise or lower its autonomy based on the measured success and risk of past actions?

**Primary use case (a service crashes):** a service instance crashes → Prometheus detects the failed health check and Alertmanager alerts STRATA → STRATA reads recent logs, matches a rule and proposes a restart → the risk score is low (one instance, easy to undo), so it restarts the service automatically → STRATA checks the metrics, confirms recovery, logs the incident and updates the restart action's success record.

**Secondary use case (a bad deployment):** a rollback scores higher risk, so the engineer must approve it on the dashboard first.

## Team

| Member | GitHub Username | Role |
|---|---|---|
| Nida Esen | [@NidaEsenn](https://github.com/NidaEsenn) | Target backend and dashboard: order and inventory services, failure switches, failure scenarios, bad-deploy and traffic-spike injection, Thymeleaf dashboard |
| Shivani Radhakrishnan | [@Shivani-Radhakrishnan](https://github.com/Shivani-Radhakrishnan) | Observability and infrastructure: Docker Compose, Prometheus, Alertmanager, Loki, Grafana, recovery verification, CI, benchmark harness |
| Madhuri Hegde | [@madhurihegde2](https://github.com/madhurihegde2) | Healer pipeline: alert webhook, detection, rule-based diagnosis, Docker actions (restart, scale out, rollback), approval workflow, orchestration |
| Saahithi Mallarapu | [@saahiwrite](https://github.com/saahiwrite) | Risk, policy, trust and audit: risk scorer, YAML policy, action validator, trust and earned autonomy, LLM diagnosis, prompt-injection defense, hash-chained audit log, persistence |

## Repository Structure

The folders follow the components in our Phase 1 system design. Packages marked *(Phase 3)* are being added in this phase.

```
strata/
  target-services/
    order-service/        Spring Boot service that STRATA heals (metrics, JSON logs, /chaos/* failure switches)
    inventory-service/    Second Spring Boot service, behind Nginx with order-service
  strata-healer/          The STRATA healer (Spring Boot, Java 21)
    src/main/java/edu/neu/strata/healer/
      model/              Shared types between the stages: Alert, Diagnosis, ActionType, AutonomyLevel, RiskAssessment, ExecutionResult
      api/                REST endpoints: Alertmanager webhook, incidents, audit and trust API; approvals (Phase 3)
      detection/          Turns incoming alerts into incidents (AlertParser, IncidentService)
      diagnosis/          Rule matcher (rules.yaml) and Loki log client; LLM diagnosis (Phase 3)
      validation/         Action allow-list; rejects unknown actions and targets
      policy/             YAML policy loader and risk scorer (score 0-100 → L0-L3)
      execution/          Docker actions (restart; scale out and rollback in Phase 3) and cooldown guard
      verification/       Prometheus client and recovery verifier
      trust/              Per-action success and failure record; earned autonomy (Phase 3)
      audit/              Tamper-evident, hash-chained audit log
      orchestration/      Healing loop: detect → diagnose → validate → score → execute → verify → audit → trust
      persistence/        Database access: read repositories and write stores (Spring JDBC)
      dashboard/          Thymeleaf dashboard (Phase 3)
    src/main/resources/
      policy.yaml         Risk weights, level thresholds, environments, per-action risk and the allow-list
      rules.yaml          Diagnosis rules (alert + optional log text → action and confidence)
      db/migration/       Flyway migrations (incidents, decisions, audit_log, action_trust)
    src/test/java/edu/neu/strata/healer/   Unit tests (JUnit 5) and integration tests (it/, Testcontainers)
  infra/
    nginx/                Load balancer config
    prometheus/           Scrape config, alert rules and promtool tests
    alertmanager/         Routes alerts to the healer's webhook
    loki/  promtail/      Log storage and log shipping
    grafana/              Data sources and the STRATA dashboard (up/down, error rate, latency, memory, logs)
  scenarios/              Catalogue of the 18 injected failures (README.md) and inject.sh
  docs/                   Phase 1 design document (phase1-design.md); benchmark results (Phase 3)
  .github/workflows/      CI: mvn verify (unit + integration tests), promtool, amtool, compose config
  .env.example            Example environment variables (copy to .env)
  pom.xml                 Parent pom (Spring Boot 3.3, Java 21); lists every Maven module
```

## Setup and Run

**Prerequisites:**
- Java 21
- Maven 3.9+
- Docker Desktop (or Docker Engine) with Docker Compose v2
- Git

No accounts or API keys are needed. The LLM diagnosis (Phase 3) uses a local Ollama model that runs in Docker Compose.

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

# Run all tests: unit tests and the Testcontainers integration tests (Docker must be running)
mvn verify
```

**Primary use case (crash → automatic restart):**

```bash
# Crash order-service through its failure switch
./scenarios/inject.sh crash order-service

# Watch STRATA detect, restart and verify it, then check the incident and audit log
curl http://localhost:9000/api/incidents
curl http://localhost:9000/api/audit
curl http://localhost:9000/api/audit/verify
```

On a fresh clone the healer restarted order-service about 25 s after the crash, and the incident showed `RESOLVED` about 20 s after that.

**Secondary use case (bad deployment → rollback with approval):** being built in Phase 3 (#41, #49, #50, #44). The commands will be `./scenarios/inject.sh scenario S14`, then approving the pending rollback on `http://localhost:9000/dashboard`.

**Other places to look:** Grafana on `http://localhost:3000` (STRATA dashboard, no login needed), Prometheus on `:9090`, Alertmanager on `:9093`. `./scenarios/inject.sh list` prints every scenario.

On macOS with Colima instead of Docker Desktop, clone the repo somewhere under your home folder (Colima only shares your home folder with containers), and `mvn verify` needs `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` for the integration tests.

**Environment variables** (`.env.example`):

| Variable | Purpose |
|---|---|
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | Database for incidents, decisions, trust records and the audit log |
| `HEALER_PORT` | Port of the STRATA healer (default 9000) |
| `POSTGRES_PORT`, `PROMETHEUS_PORT`, `ALERTMANAGER_PORT`, `LOKI_PORT`, `GRAFANA_PORT` | Optional host ports (5432, 9090, 9093, 3100, 3000). Change only if a port is taken |
| `GRAFANA_ADMIN_PASSWORD` | Grafana admin password (default `admin`). Dashboards can be viewed without logging in |
| `DOCKER_HOST` | Only if docker-java can't find the Docker socket (some macOS/Windows setups) |

## Feature Status

Status as of the start of Phase 3. The issue numbers are the Phase 3 work that completes each requirement ([Phase 3 milestone](https://github.com/saahiwrite/strata/milestone/2)).

| Functional Requirement (from Phase 1) | Status | Where to Find It |
|---|---|---|
| FR1: Services expose metrics, JSON logs and failure switches (error, slow, crash, memory growth) | Complete | `target-services/*/chaos/` (`ChaosController`, `ChaosFilter`, `MemoryLeak`, `Terminator`), `logback-spring.xml`, `/actuator/prometheus`; Phase 3 adds a log switch (#42) and a broken release (#41) for S14-S17 |
| FR2: Alert on defined thresholds and open an incident | Complete | `infra/prometheus/alerts.yml` (`ServiceDown`, `HighErrorRate`), `infra/alertmanager/alertmanager.yml`, `api/AlertWebhookController`, `detection/AlertParser`, `detection/IncidentService`; `HighLatency`/`HighMemory` (#39) and Nginx down (#45) in Phase 3 |
| FR3: Diagnose with rules first; use the LLM when no rule matches | Partial | Rules: `diagnosis/RuleMatcher`, `rules.yaml`, `diagnosis/LokiLogClient`. LLM with Ollama: #52 |
| FR4: Treat logs as untrusted and run only allow-listed actions | Partial | `validation/ActionValidator`, `policy/AllowList`, `allowList` in `policy.yaml`. Prompt-injection defense: #53 |
| FR5: Score each action by blast radius, reversibility, environment, diagnosis confidence and past failures | Complete | `policy/RiskScorer`, `policy/PolicyLoader`, `policy.yaml` |
| FR6: Map the score to L0 auto, L1 auto with undo, L2 approval, L3 human only | Complete | `policy/RiskScorer`, `thresholds` and `minLevel` in `policy.yaml`, `model/AutonomyLevel`, `orchestration/HealingOrchestrator` |
| FR7: Support restart, scale out and rollback | Partial | Restart: `execution/DockerActionExecutor`, `execution/CooldownGuard`. Scale out, rollback and canary: #49 |
| FR8: For L2, show a preview, wait for approval, fall back to a safe action on timeout | Partial | L2 decisions are saved as `AWAITING_APPROVAL` and audited (`HealingOrchestrator`). Approve/deny API and timeout fallback: #50; dashboard buttons: #44 |
| FR9: Verify recovery; roll back and escalate if the service did not recover | Partial | `verification/PrometheusRecoveryVerifier`, `verification/PrometheusClient`; a failed verify escalates today. Undo before escalating: #51 |
| FR10: Promote or demote each action's level from its success record | Partial | `trust/TrustService`, `trust/TrustRecord`, `GET /api/trust` (successes and failures feed the past-failures factor). Earned autonomy: #54 |
| FR11: Write every decision to a tamper-evident audit log | Complete | `audit/HashChain`, `audit/AuditLogService`, `persistence/AuditLogRepository`, `GET /api/audit`, `GET /api/audit/verify` |
| FR12: Show incidents, approvals and action levels on a dashboard | Partial | JSON API: `api/IncidentController`, `api/AuditController`, `api/TrustController`. Thymeleaf dashboard: #44 |

**Working end to end:** the primary use case (S01, crash → automatic restart), from `docker compose up --build`, and in `CrashRecoveryIT`. The secondary use case (rollback with approval) is the main Phase 3 goal.

**Benchmark (Phase 1 evaluation):** 18 scenarios in `scenarios/README.md`; S01-S12 can be injected today. The harness that runs them three ways and measures MTTR, auto-resolution rate, false-fix rate, approvals per incident and injection block rate is #46.

## Scope and Design Changes Since Phase 1

| Change | Reason |
|---|---|
| Two target services (order-service, inventory-service) instead of "2 to 3" | Two services behind Nginx cover every scenario in the catalogue (single crash, both crashing, one slow while the other is healthy); a third would add build time without a new failure type |
| A shared `model/` package (`Alert`, `Diagnosis`, `ActionType`, `AutonomyLevel`, `RiskAssessment`, `ExecutionResult`) and the `ActionExecutor`/`RecoveryVerifier` interfaces were agreed on day 1 of Phase 2 | Four people built the stages in parallel; fixed types and interfaces let each stage be built and tested against fakes before the others existed |
| Spring JDBC (`JdbcClient`) with Flyway migrations instead of an ORM; reads (`*Repository`) and writes (`*Store`) are separate classes | The audit log must be append-only and its hash chain is computed over exact column values, so we wanted explicit SQL; separating reads from writes keeps the read-only API from being able to change anything |
| Unit tests run the real Flyway migrations on H2 in PostgreSQL mode; only the integration tests use real PostgreSQL (Testcontainers) | Keeps `mvn test` fast and Docker-free while still checking the schema; the ITs cover PostgreSQL itself |
| Resilience4j (listed in the Phase 1 stack) is not used | Restart loops are prevented by our own `CooldownGuard` (60 s per target) and the verification timeout, which the policy and audit log need to see; a separate library added nothing for that |
| Nginx going down (S18) opens an incident but is never healed (L3, human only) | Nginx is shared by every service and is not in the allow-list, so restarting it automatically would have the largest blast radius in the system |
| The approval dashboard moved from Saahithi's Phase 3 work to Nida's | Balances the Phase 3 work across the four members; the dashboard only calls the approval API Madhuri builds |

## Design Patterns and Principles Applied

| Pattern / Principle | Where | Why |
|---|---|---|
| Layered architecture / separation of concerns | `api` → `orchestration` → stage packages (`detection`, `diagnosis`, `validation`, `policy`, `execution`, `verification`, `trust`, `audit`) → `persistence` | Controllers only parse requests and call services; each package owns one stage of the healing loop, so a change to risk scoring never touches Docker code (high cohesion, low coupling) |
| Pipeline (fixed sequence of stages) | `orchestration/HealingOrchestrator.run` | Every incident goes through the same ordered stages (diagnose → validate → score → execute → verify → audit → trust), so no path can skip validation or the audit log |
| Strategy / Dependency Inversion | `execution/ActionExecutor` (`DockerActionExecutor`), `verification/RecoveryVerifier` (`PrometheusRecoveryVerifier`) | The orchestrator depends on interfaces, not on Docker or Prometheus; Phase 3 adds scale-out and rollback executors and tests swap in mocks |
| Dependency Injection (constructor injection) | All services; `@Configuration` classes `PolicyConfig`, `AuditConfig`, `DockerConfig`, `VerificationConfig`, `TrustConfig` | Every dependency is explicit and replaceable; `HealingOrchestratorTest` builds the orchestrator with mocks and a same-thread executor |
| Repository pattern with read/write separation | `persistence/IncidentRepository`, `AuditLogRepository`, `TrustRepository` (read) and `IncidentStore`, `DecisionStore` (write) | SQL stays in one package; the read-only API can't write |
| Configuration over code (Open/Closed) | `policy.yaml`, `rules.yaml` | New rules, weights, thresholds and allowed targets need no code change (Phase 1 usability requirement: "policy changes need only a YAML edit") |
| Fail fast | `policy/PolicyLoader`, `PolicyConfig` | A policy whose weights don't add up to 100 or that has unknown actions stops the healer at startup instead of scoring wrongly |
| Immutable value objects (Java records) | `model/*`, `Rule`, `Policy`, `AuditEntry`, `TrustRecord`, `ValidationResult` | Data passed between stages can't be changed by a later stage; records validate their fields in their constructors |
| Hash chain (tamper evidence) | `audit/HashChain`, `audit/AuditLogService` | Each audit entry stores the SHA-256 of the previous one, so a changed, deleted or reordered entry breaks `GET /api/audit/verify` |
| Allow-list (default deny) | `validation/ActionValidator`, `policy/AllowList` | Logs are untrusted input: anything a diagnosis proposes that isn't explicitly listed is rejected before it is scored |

## Testing

- **Unit tests (JUnit 5, Mockito, MockMvc):** 157 in `strata-healer` (shared types, policy loader, risk scorer, allow-list, action validator, hash chain, audit log service including tampering and 40 concurrent appends, schema migrations, trust, API, webhook, alert parser, incidents, rule matcher, cooldown, Docker executor, Loki client, orchestrator, Prometheus client and recovery verifier), and 23 each in `order-service` and `inventory-service` (API, metrics, JSON logging, every chaos switch). The persistence tests run the real Flyway migrations on H2 in PostgreSQL mode.
- **Integration tests (Testcontainers):** `CrashRecoveryIT` kills a real container, posts a `ServiceDown` alert and checks that the healer restarts it, resolves the incident and keeps the audit chain valid, on real PostgreSQL. Phase 3 adds `RollbackApprovalIT` (#50) and `PromptInjectionIT` (#53).
- **Monitoring config:** promtool unit tests for the alert rules (`infra/prometheus/tests/alerts-test.yml`); CI also checks the Prometheus and Alertmanager configs and `docker-compose.yml`.
- **Coverage:** JaCoCo report in CI is being added (#47).

All of these run in GitHub Actions on every push and pull request (`.github/workflows/ci.yml`), and pass on `main`.

```bash
mvn test                                   # unit tests, all modules (no Docker needed)
mvn verify                                 # unit + integration tests (Docker must be running)
mvn -pl strata-healer test                 # healer unit tests only
mvn -pl strata-healer verify -Dit.test=CrashRecoveryIT   # one integration test
docker run --rm -v "$PWD/infra/prometheus:/p" -w /p/tests --entrypoint promtool prom/prometheus:v3.5.0 test rules alerts-test.yml
```

## Known Issues

- An L2 decision currently stops at `AWAITING_APPROVAL` with no way to approve or deny it (approval API #50, dashboard #44).
- An error no rule matches is escalated straight to a human, because LLM diagnosis isn't built yet (#52).
- `HighLatency` and `HighMemory` alerts don't exist yet, so S08-S12 don't fire an alert (#39, in review).
- Failure switches live in memory, so a restart resets them. This is intended (it's what lets a restart fix S05-S12), but it means a switch can't survive a restart for testing.
- The integration tests need Docker; on Colima they need the two extra environment variables above.

## Team Contributions (Phase 3)

> Each member updates their own row with exactly what they built this phase (features, classes, files, tests and reviews). Phase 3 issues are on the [milestone](https://github.com/saahiwrite/strata/milestone/2).

| Member | Contributions This Phase |
|---|---|
| Nida Esen | _To be filled in (#41 bad-deploy image, #42 chaos log switch, #43 traffic spike, #44 dashboard)_ |
| Shivani Radhakrishnan | _To be filled in (#39 latency and memory alerts, #45 Nginx down alert, #46 benchmark harness, #47 CI coverage and ITs, #48 fresh-clone check)_ |
| Madhuri Hegde | _To be filled in (#49 scale out, rollback and canary, #50 approval workflow, #51 roll back and escalate)_ |
| Saahithi Mallarapu | Planned Phase 3 (milestone and issues #41-#56) and moved this README to the Phase 3 template (feature status, scope changes, design patterns, testing, known issues). _To be completed (#52 LLM diagnosis, #53 prompt-injection defense, #54 earned autonomy, #55 submission)_ |

<details>
<summary>Phase 2 contributions</summary>

| Member | Contributions in Phase 2 |
|---|---|
| Nida Esen | Built `order-service` and `inventory-service` (Spring Boot 3, Actuator, Prometheus metrics, JSON logs via Logback); the failure switches (crash, error rate, slow responses, memory growth, reset) with their metrics; the Nginx entry point on :8080; the 18-scenario failure catalogue and `scenarios/inject.sh`; `ChaosControllerTest`, `ChaosStateTest`, `MemoryLeakTest`, `OrderControllerTest`, `InventoryControllerTest`, `JsonLoggingTest` |
| Shivani Radhakrishnan | Docker Compose file with PostgreSQL, Prometheus, Alertmanager, Loki, Promtail and Grafana; Prometheus scrape config; `ServiceDown` and `HighErrorRate` alert rules with promtool tests; Alertmanager webhook to the healer; Grafana dashboard (up/down, error rate, latency, memory, logs); `PrometheusClient` and `PrometheusRecoveryVerifier` with unit tests (#23-#26); added the services, Nginx and the healer to Compose, `CrashRecoveryIT` and the GitHub Actions CI (#15) |
| Madhuri Hegde | Built the healer pipeline in `strata-healer`.<br>**Skeleton and webhook (#19):** the Spring Boot application, its package layout and `POST /api/alerts`, which accepts Alertmanager JSON and returns 202.<br>**Detection (#28):** `AlertParser`, which turns the webhook payload into alerts and skips resolved or incomplete ones, and `IncidentService`, which opens, resolves and escalates incidents and reuses the open incident when Alertmanager repeats an alert.<br>**Diagnosis and execution (#29):** `RuleMatcher` with `rules.yaml` (a crash maps to a restart, with a more specific rule when the logs show `OutOfMemoryError`); `LokiLogClient`; `DockerActionExecutor`, which restarts a named container through docker-java; and `CooldownGuard`.<br>**Orchestration (#33):** `HealingOrchestrator`, which runs an alert through diagnosis, validation, risk scoring, execution, recovery verification, the audit log and the trust record, and handles L2 (waits for approval), L3 (hands to a human) and failures (escalates). It saves incidents and decisions to the database (`IncidentStore`, `DecisionStore`), and the `V2` migration fixes the `decisions.outcome` column, which was too short for `AWAITING_APPROVAL`.<br>**Tests:** `AlertWebhookControllerTest`, `AlertParserTest`, `IncidentServiceTest`, `RuleMatcherTest`, `CooldownGuardTest`, `DockerActionExecutorTest`, `LokiLogClientTest` and `HealingOrchestratorTest` (10 cases). |
| Saahithi Mallarapu | Set up the repository (folder scaffold, `.env.example`, `.gitignore`, labels, the Phase 2 milestone and issues), and wrote this README and the Phase 1 design doc (`docs/phase1-design.md`).<br>**Shared foundation (#20):** the parent `pom.xml`, and the types every healing stage uses: `Alert`, `Diagnosis`, `ActionType`, `AutonomyLevel`, `RiskAssessment`, `ExecutionResult`, `ActionExecutor` and `RecoveryVerifier`.<br>**Risk policy (#21):** `policy.yaml` (factor weights, level thresholds, environments, per-action risk); `PolicyLoader`, which reads it safely and stops startup on a bad policy; and `RiskScorer`, which turns blast radius, reversibility, environment, diagnosis confidence and past failures into a score of 0-100 and a level from L0 to L3.<br>**Validation and audit (#22):** the action allow-list and `ActionValidator`, which rejects unlisted actions and target names injected through logs; the Flyway schema (`incidents`, `decisions`, `action_trust`, `audit_log`); and the SHA-256 hash-chained audit log (`HashChain`, `AuditLogService`), which detects changed, deleted, reordered or forged entries.<br>**Trust and API (#30):** `TrustService`, which keeps each action's successes and failures and feeds them into the risk score; and the read-only endpoints `GET /api/incidents`, `/api/incidents/{id}`, `/api/audit`, `/api/audit/verify` and `/api/trust`.<br>**Tests:** 105 JUnit 5 tests in the healer, run by `mvn verify`, including `PolicyLoaderTest`, `RiskScorerTest`, `ActionValidatorTest`, `HashChainTest`, `AuditLogServiceTest` (tampering and 40 concurrent appends), `SchemaMigrationTest`, `TrustServiceTest` and `ApiTest`.<br>**End of Phase 2 (#31):** brought the README's progress, testing and structure sections up to date. |

</details>
