# STRATA: Self-healing with Trust and Risk-Adaptive Autonomy

**Phase 1 design document** (submitted October 2026), copied here as Markdown so the repository has the design the code follows. The architecture diagram from the submitted PDF isn't reproduced; the components below and the README's Repository Structure describe the same design.

**Team:** Nida Esen, Shivani Radhakrishnan, Madhuri Hegde, Saahithi Mallarapu

## Problem statement

When a backend service crashes, slows down or gets overloaded, an on-call engineer has to read logs, find the cause and fix it by hand. That is slow and needs someone to stay awake. Fully automatic fixes are risky: a wrong restart or rollback can make an outage worse, and an AI that reads logs can be tricked by text an attacker puts in them.

We need a system that fixes safe problems on its own, asks a human before risky ones, and adjusts its own freedom based on how well past fixes worked.

As backend systems grow into many connected services, failures become harder to find and fix, which makes observability essential. OpenTelemetry has become the industry standard for collecting metrics, logs and traces, letting teams understand what their systems are doing in real time. This matters even more as AI agents begin handling incident response, since an automated system can only diagnose and fix problems if it has reliable telemetry to work from. STRATA is built on this idea: it uses observability data to drive every diagnosis, risk decision and recovery check.

## Objective

Build STRATA, a Java self-healing backend that detects failures, diagnoses them, scores each fix for risk, and then runs it, asks for approval, or hands it to a human. It also blocks prompt injection through logs and is measured on 15 to 20 injected failures.

**Research question:** can a backend safely raise or lower its autonomy based on the measured success and risk of past actions?

## Use cases

**Primary (user: on-call engineer): a service crashes**
1. A service instance crashes.
2. Prometheus detects the failed health check, and Alertmanager alerts STRATA.
3. STRATA reads recent logs, matches a rule and proposes a restart.
4. The risk score is low (one instance, easy to undo), so STRATA restarts it automatically.
5. STRATA checks the metrics, confirms recovery, logs the incident and updates the restart action's success record.

**Secondary: a bad deployment raises errors.** A rollback scores higher risk, so the engineer must approve it on the dashboard first.

## Scope

**In scope**
- 2 to 3 Spring Boot services behind Nginx, run with Docker Compose
- Monitoring: Prometheus, Loki, Grafana, Alertmanager
- STRATA healer: detect, diagnose, risk-score, execute, verify, roll back
- Diagnosis: rules first, local LLM for unknown errors
- Risk levels L0 to L3, set in a YAML policy
- Dashboard for approving risky actions
- Earned autonomy: action levels move up or down with results
- Prompt-injection defense, action allow-list, audit log
- Benchmark of 15 to 20 injected failures

**Out of scope**
- Kubernetes, multiple LLMs, vector memory, postmortems, forecasting, code patches
- Live comparison against other AI SRE tools
- Any action that changes database contents

## Tech stack

| Part | Technologies |
|---|---|
| Target backend | Java 21, Spring Boot 3, PostgreSQL, Nginx, Docker Compose |
| Monitoring | Prometheus, Loki with Promtail, Grafana, Alertmanager, Logback (JSON logs) |
| STRATA healer | Java 21, Spring Boot, docker-java, Resilience4j, YAML policy, Thymeleaf dashboard |
| AI diagnosis | Spring AI or LangChain4j with Ollama (local model) |
| Testing | JUnit 5, Testcontainers, k6 |
| Tools | Git and GitHub, Maven, IntelliJ IDEA |

## System design overview

Prometheus and Loki collect data from the services. Alertmanager alerts STRATA, which diagnoses (rules, then LLM), checks the proposed action against an allow-list, scores its risk, and runs it through the Docker API. It then checks the metrics, and the result updates that action's trust record.

**Components**
- Target backend: Spring Boot services behind Nginx
- Observability: Prometheus, Loki, Grafana, Alertmanager
- STRATA healer: detect, diagnose, validate, score, execute, verify
- Risk and policy engine: risk scorer, YAML policy, trust record per action
- LLM (Ollama): diagnosis of unknown errors only
- Dashboard and audit log: approvals, incidents, decision history

### Shared types (added in Phase 2)

The healing stages talk to each other through a few shared types, so each stage can be built and tested on its own. They live in `strata-healer` under `edu.neu.strata.healer`:

| Type | Package | Purpose |
|---|---|---|
| `Alert` | `model` | One firing alert: name, service, container, severity, start time |
| `Diagnosis` | `model` | Matched rule, proposed action, target, confidence (0.0-1.0) |
| `ActionType` | `model` | `RESTART`, `SCALE_OUT`, `ROLLBACK` |
| `AutonomyLevel` | `model` | `L0_AUTO`, `L1_AUTO_UNDO`, `L2_APPROVAL`, `L3_HUMAN_ONLY` |
| `RiskAssessment` | `model` | Score (0-100), level and each factor's contribution |
| `ExecutionResult` | `model` | Outcome of running one action |
| `ActionExecutor` | `execution` | Runs an action against Docker |
| `RecoveryVerifier` | `verification` | Checks whether a service recovered within a timeout |

## External interface requirements

**User interfaces**
- Approval dashboard: pending risky actions with a preview, Approve and Deny buttons
- Incident and audit log view: diagnosis, risk score, level and outcome of each decision
- Grafana dashboards: error rate, latency, memory

**Hardware interfaces:** none; runs on a laptop with Docker.

**Software interfaces**
- Docker Engine API (restart, scale, roll back)
- Prometheus and Loki HTTP APIs (metrics and logs)
- Alertmanager webhook (alerts to STRATA)
- Ollama local API (LLM diagnosis, JSON)
- PostgreSQL (incidents, decisions, action statistics)
- Datasets: none; failures are injected by the team

## Functional requirements

| # | Requirement |
|---|---|
| FR1 | Services expose metrics, JSON logs and failure switches (error, slow, crash, memory growth) |
| FR2 | Alert on defined thresholds and open an incident |
| FR3 | Diagnose with rules first; use the LLM when no rule matches |
| FR4 | Treat logs as untrusted and run only allow-listed actions |
| FR5 | Score each action by blast radius, reversibility, environment, diagnosis confidence and past failures |
| FR6 | Map the score to L0 auto, L1 auto with undo, L2 approval or L3 human only |
| FR7 | Support restart, scale out and rollback |
| FR8 | For L2, show a preview, wait for approval and fall back to a safe action on timeout |
| FR9 | Verify recovery; roll back and escalate if the service did not recover |
| FR10 | Promote or demote each action's level from its success record |
| FR11 | Write every decision to a tamper-evident audit log |
| FR12 | Show incidents, approvals and action levels on a dashboard |

## Non-functional requirements

- **Performance:** rule-based incidents handled within seconds; LLM diagnosis time is measured and reported
- **Reliability:** cooldowns prevent restart loops; STRATA runs outside the services it heals; a failed fix always ends in rollback or escalation
- **Usability:** one command starts everything (`docker compose up`); policy changes need only a YAML edit
- **Security:** logs are untrusted input; the LLM proposes and Java code decides; least-privilege credentials; hash-chained audit log

## Estimated timeline

1. **Foundation (weeks 1-2):** services with metrics, JSON logs and failure switches; Docker Compose with Nginx, PostgreSQL, Prometheus, Loki, Grafana; define the 15 to 20 failure scenarios
2. **Core healing (weeks 3-4):** week 3: alert webhook, restart with cooldown, first end-to-end demo; week 4: restart, scale and rollback actions, risk scorer, L0 to L3, YAML policy, audit log
3. **Intelligence and safety (weeks 5-6):** week 5: rule matcher, LLM diagnosis, action validator, injection defense; week 6: approval dashboard, recovery check, canary rollout, earned autonomy
4. **Evaluation (week 7):** run all scenarios three ways (manual fix, rules only, full STRATA); run the injection test set and analyze results
5. **Delivery (week 8):** README, final report, demo video, presentation

## Testing and success criteria

**How we test**
- Unit tests (JUnit 5): risk scorer, policy loader, action validator, rule matcher, autonomy rules, audit hash chain
- Integration tests (Testcontainers): alert to action against real containers
- Benchmark: 15 to 20 injected failures, including crash, slow responses, traffic spike, bad deployment, memory growth and malicious log lines
- Each scenario runs three ways: manual fix, rules only (no risk gate), full STRATA

**What we measure:** MTTR, auto-resolution rate, false-fix rate, approvals per incident over time, injection block rate.

**Success criteria**
- `docker compose up` starts the system, and it heals a crash with no human input
- No L3 action ever runs automatically
- Every decision appears in the audit log
- On crash, overload and bad deployment, STRATA recovers faster than the manual baseline
- False-fix rate and injection block rate are measured and reported
