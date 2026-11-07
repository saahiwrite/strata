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
  docs/                   Phase 1 design document and other project docs
  .github/workflows/      CI (mvn verify)
  .env.example            Example environment variables (copy to .env)
```

## Setup and Run

> The system is being built during Phase 2 (Nov 3-7, 2026). The commands below are how it will run once the Compose file and Maven modules are merged. We'll update this section if any step changes.

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
| `DOCKER_HOST` | Only if docker-java can't find the Docker socket (some macOS/Windows setups) |

## Progress So Far

| Functional Requirement (from Phase 1) | Status | Notes |
|---|---|---|
| FR1: Services expose metrics, JSON logs and failure switches (error, slow, crash, memory growth) | Done | order-service and inventory-service (both on :8080 in their containers) behind Nginx (:8080 on the host), `/actuator/prometheus`, JSON logs on stdout, `/chaos/crash\|error\|slow\|memory\|reset`; 18-scenario catalogue and `scenarios/inject.sh` |
| FR2: Alert on defined thresholds and open an incident | Not started | Phase 2: `ServiceDown` and `HighErrorRate` rules, webhook to the healer |
| FR3: Diagnose with rules first; use the LLM when no rule matches | Not started | Phase 2: rule matcher. Phase 3: LLM (Ollama) |
| FR4: Treat logs as untrusted and run only allow-listed actions | Not started | Phase 2: action validator. Phase 3: prompt-injection defense |
| FR5: Score each action by blast radius, reversibility, environment, diagnosis confidence and past failures | Not started | Phase 2: risk scorer with YAML weights |
| FR6: Map the score to L0 auto, L1 auto with undo, L2 approval, L3 human only | Not started | Phase 2 |
| FR7: Support restart, scale out and rollback | Not started | Phase 2: restart. Phase 3: scale out and rollback |
| FR8: For L2, show a preview, wait for approval, fall back to a safe action on timeout | Not started | Phase 3 |
| FR9: Verify recovery; roll back and escalate if the service did not recover | Not started | Phase 2: recovery verifier. Phase 3: rollback and escalation |
| FR10: Promote or demote each action's level from its success record | Not started | Phase 2: trust records. Phase 3: earned autonomy |
| FR11: Write every decision to a tamper-evident audit log | Not started | Phase 2: SHA-256 hash chain |
| FR12: Show incidents, approvals and action levels on a dashboard | Not started | Phase 2: JSON API. Phase 3: Thymeleaf dashboard |

**Working end to end:** nothing yet. So far the repository has the folder layout that matches our Phase 1 design, an `.env.example` and a `.gitignore`. By the end of Phase 2 the goal is that `docker compose up` starts everything and a crashed service is detected, restarted and verified with no human input.

## Testing So Far

- **Target services:** 23 unit and MockMvc tests in each of `order-service` and `inventory-service` (API, Prometheus metrics, JSON logging, every chaos switch and its input validation). Run with `mvn verify` inside each service folder.
- Planned for Phase 2:
  - **Unit tests (JUnit 5):** `PolicyLoaderTest`, `RiskScorerTest`, `ActionValidatorTest`, `HashChainTest`, `TrustServiceTest`, `AlertParserTest`, `RuleMatcherTest`, `CooldownGuardTest`, `ChaosControllerTest`
  - **Integration test (Testcontainers):** `CrashRecoveryIT`
  - All of them run in GitHub Actions CI with `mvn verify`.

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
| Shivani Radhakrishnan | _To be filled in_ |
| Madhuri Hegde | _To be filled in_ |
| Saahithi Mallarapu | Created the public repository, folder scaffold, `.env.example`, `.gitignore`, labels, Phase 2 milestone and issues; wrote this README |
