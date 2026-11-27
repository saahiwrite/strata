# QA: monitoring, alerts, verifier, docker-proxy and CI

Part of the Phase 4 QA document (#82). This covers Shivani's area: Prometheus
alert rules, Alertmanager, Loki/Promtail/Grafana, the recovery verifier, the
Docker socket proxy and CI. The other sections of the QA doc cover the healer
rules, policy and target services.

Every result below comes from a real run on 2026-11-27, on main at `4d073da`.

## How we test this part

- **Alert rules: unit tests with promtool.** `promtool test rules` feeds fake
  time series into the rules and checks which alerts fire and when. This is fast,
  needs no running services, and tests the exact thresholds and `for:` times.
- **Verifier and Docker client: JUnit unit tests.** `PrometheusClient` runs against
  a fake HTTP server. `PrometheusRecoveryVerifier` gets a stub client and a fake
  clock, so timeouts are tested without waiting.
- **Integration tests (Testcontainers).** `CrashRecoveryIT` runs the healer against
  real containers: crash a service, the healer restarts it, the verifier confirms it.
- **Live checks on the Compose stack.** Things unit tests can't show: all targets
  scraped, logs reaching Loki, the proxy's allow list, and the real time from a
  crash to an alert to recovery.
- **CI.** Every push and PR runs `mvn -B verify` (unit tests, ITs, JaCoCo) and the
  infra job (promtool, amtool, `docker compose config`).

## Scope

Tested: the 5 alert rules (ServiceDown, HighErrorRate, HighLatency, HighMemory,
GatewayDown), Prometheus and Alertmanager config, `PrometheusClient`,
`PrometheusRecoveryVerifier`, `VerificationConfig`, `FreshConnectionDockerHttpClient`,
the docker-proxy allow list, Loki log intake, Grafana provisioning, CI jobs.

Not tested here:
- Promtail parsing of every log field. We only check that logs from every container arrive.
- Grafana panel contents. We check the dashboard is provisioned, not each query.
  The healer metrics row (#64) is not merged yet.
- Alertmanager grouping and repeat timing. There is one webhook receiver and the
  healer deduplicates incidents itself.
- Load and long-running tests. The benchmark (#46, `docs/benchmark/`) covers repeated runs.

## Environment

| Item | Version |
|---|---|
| OS | macOS 27.0.1 (Apple silicon); CI runs on ubuntu-latest |
| Docker | Colima 0.10.3 (3 GB VM), Docker Engine 29.5.2, Compose 5.7.0 |
| Java / Maven | Java 21 (Temurin in CI, 21.0.1 locally), Maven 3.10.0 |
| Prometheus / promtool | prom/prometheus v3.5.0 |
| Alertmanager / amtool | prom/alertmanager v0.28.1 |
| Grafana | 12.0.0 |
| Docker proxy | tecnativa/docker-socket-proxy v0.5.0 |
| Test tools | JUnit 5, Testcontainers, JaCoCo, curl (curlimages/curl 8.11.1) |
| Test data | Series written in `infra/prometheus/tests/alerts-test.yml`; fake Prometheus HTTP server in `PrometheusClientTest` |

## Test cases

### Alert rules (promtool)

Run: `promtool test rules infra/prometheus/tests/alerts-test.yml`

| ID | Test | Req | Expected | Observed | Status |
|---|---|---|---|---|---|
| QA-INF-01 | order-service stops being scraped at 30s; check at 35s and 45s | FR2 | Pending at 35s (down 5s). Firing at 45s with `service` and `container` labels. inventory-service quiet. | As expected | Pass |
| QA-INF-02 | Service down since the start (still booting) | FR2 | No ServiceDown at 35s or 55s, so the healer doesn't restart a service that is just starting | No alert | Pass |
| QA-INF-03 | Prometheus itself down | FR2 | No ServiceDown (not a target service) | No alert | Pass |
| QA-INF-04 | 50% 5xx on order-service, 10% on inventory-service, plus actuator and /chaos traffic | FR2 | HighErrorRate fires for order-service only. Actuator and /chaos calls are ignored. | As expected | Pass |
| QA-INF-05 | Every order-service request takes 2s, inventory-service under 0.1s | FR2 | HighLatency pending at 20s, firing at 2m for order-service only. Slow /chaos calls ignored. | As expected | Pass |
| QA-INF-06 | No traffic at all | FR2 | p95 is NaN, so no HighLatency | No alert | Pass |
| QA-INF-07 | order-service heap ~90% full, inventory-service ~39% | FR2 | HighMemory pending at 10s, firing at 2m for order-service only. Pools with max -1 and non-heap ignored. | As expected | Pass |
| QA-INF-08 | Nginx probe fails from 30s | FR2 | GatewayDown pending at 40s, firing at 50s. ServiceDown does not fire for the gateway. | As expected | Pass |
| QA-INF-09 | Gateway never came up | FR2 | No GatewayDown | No alert | Pass |
| QA-INF-10 | `promtool check config` and `amtool check-config` | FR2 | Both configs valid | "SUCCESS: 5 rules found"; Alertmanager config valid, 1 receiver | Pass |

### Verifier and Docker client (JUnit)

Run: `mvn -B verify` (all modules). Counts are per test class.

| ID | Test | Req | Expected | Observed | Status |
|---|---|---|---|---|---|
| QA-INF-11 | `PrometheusClientTest` (14 tests): vector result, empty result, NaN, +Inf/-Inf, bad query, HTTP 500, non-vector result, empty body, error status with and without an `error` field, missing status, missing or non-numeric value, Prometheus unreachable | FR9 | Values parsed; every error becomes a `PrometheusException` with Prometheus's message | 14/14 pass (after the D-4 fix) | Pass |
| QA-INF-12 | `PrometheusRecoveryVerifierTest` (12 tests): 3 healthy checks in a row = recovered; still down until timeout = not recovered; one bad check resets the count; error rate above the limit is unhealthy; no traffic counts as no errors; every instance must be up; no `up` series is unhealthy | FR9 | Verdicts as listed | 12/12 pass | Pass |
| QA-INF-13 | Same class: Prometheus error keeps trying; queries use the `service` label and skip actuator/chaos; service names with quotes or braces are rejected (PromQL injection); interrupt stops waiting; bad settings rejected | FR4, FR9 | As listed | Included in the 12 above, all pass | Pass |
| QA-INF-14 | `VerificationConfigTest` (2 tests): default wiring; a bad setting stops startup | FR9 | Verifier built with defaults; a max error rate of 2 fails startup | 2/2 pass | Pass |
| QA-INF-15 | `FreshConnectionDockerHttpClientTest` (4 tests): every request sends `Connection: close`; other headers and body kept; hijacked streams left alone; close closes the real client | FR7 | As listed | 4/4 pass | Pass |
| QA-INF-16 | `CrashRecoveryIT` (Testcontainers): crash a real service container, healer restarts it, verifier confirms | FR7, FR9 | Incident resolved as recovered | 1/1 pass, 10.7s | Pass |

### Live checks on the Compose stack

Run: `docker compose up -d --build`, then curl against the published ports. The
proxy checks run curl in a container on the `strata_docker-api` network,
the same network the healer uses.

| ID | Test | Req | Expected | Observed | Status |
|---|---|---|---|---|---|
| QA-INF-17 | Prometheus targets after a cold start | FR1 | prometheus, order-service, inventory-service and gateway all `up` | All 4 up; 139s from `compose up` (includes image build) | Pass |
| QA-INF-18 | Rules loaded in the running Prometheus | FR2 | 5 rules | ServiceDown, HighErrorRate, HighLatency, HighMemory, GatewayDown | Pass |
| QA-INF-19 | Logs from every container reach Loki | FR3 | Every container shows up as a `container` label | 12 of 12 containers listed | Pass |
| QA-INF-20 | Grafana up with the dashboard provisioned | FR12 | `/api/health` ok, "STRATA target services" dashboard present | database ok, Grafana 12.0.0, dashboard in the STRATA folder | Pass |
| QA-INF-21 | Proxy allows what the healer needs | FR7 | `GET /containers/json` = 200 | 200 | Pass |
| QA-INF-22 | Proxy blocks the rest | FR4 | 403 for `/images/json`, `/v1.43/images/json`, `/volumes`, `/info`, `/events`, `POST /build`, `POST /exec/{id}/start`, `GET /exec/{id}/json` | All 403 | Pass |
| QA-INF-23 | Proxy blocks creating an exec | FR4 | `POST /containers/{id}/exec` = 403 | **201**, exec instance created (it can't be started, see D-1) | Fail |
| QA-INF-24 | End to end: `docker stop` order-service | FR2, FR7, FR9 | Alert reaches Alertmanager, healer restarts the container, verifier confirms | ServiceDown in Alertmanager after 15s, container running again after 23s, verifier "recovered (3 healthy checks in a row)" 12.7s after the restart | Pass |

### CI

| ID | Test | Req | Expected | Observed | Status |
|---|---|---|---|---|---|
| QA-INF-25 | `mvn -B verify` locally | all | All tests pass | 315 unit tests in strata-healer, 23 + 23 in the two target services, 8 ITs; 0 failures; 58.6s | Pass |
| QA-INF-26 | Coverage summary script | NFR (tests) | Table per module plus total | Total line 94.9%, branch 86.4% (table below) | Pass |
| QA-INF-27 | Infra job checks (promtool, amtool, `docker compose config -q`) | FR2 | All pass | All pass locally; same steps green on main in CI | Pass |

## Defects

| ID | Found by | Problem | Status |
|---|---|---|---|
| D-1 | QA-INF-23 | docker-proxy lets `POST /containers/{id}/exec` through, because `CONTAINERS=1` and `POST=1` allow every POST under `/containers`. The exec instance is created, but `POST /exec/{id}/start` is denied (`EXEC=0`), so no command runs. The compose comment said exec gets 403, which was wrong. | Open, low risk. Comment in `docker-compose.yml` corrected in this PR. A real fix needs our own HAProxy rule for `/containers/*/exec`, tracked for later. |
| D-2 | Live run (#75) | The first restart in almost every incident failed with `NoHttpResponseException`: the proxy closes idle keep-alive connections after 10s and the pooled client reused a dead one. | Fixed in #75 (`FreshConnectionDockerHttpClient`), covered by QA-INF-15 and QA-INF-24. |
| D-3 | Live run (#40) | HighMemory at 80% never fired: the JVM hit OutOfMemoryError at about 80% heap with the `/chaos/memory` leak. | Fixed in #40, threshold now 50%, covered by QA-INF-07. |
| D-4 | QA-INF-11 | `PrometheusClient` used `Double.parseDouble` on sample values. Java reads `NaN` but not Prometheus's `+Inf` / `-Inf`, and a missing or bad value threw `NumberFormatException`. The verifier only catches `PrometheusException`, so one bad sample would escape the recovery check instead of counting as "not recovered yet". | Fixed: `+Inf`/`-Inf` parsed, bad values wrapped in `PrometheusException`. Covered by QA-INF-11. |

Known limitation, not a test failure: the proxy has to allow `POST /containers/create`
and start for scale out and rollback. It can't check what is inside the create
request, so in theory a compromised healer could create a privileged container.
We did not try this live. The healer only creates copies of existing service
containers (#70).

## Coverage

From JaCoCo on the same run (`python3 .github/scripts/coverage_summary.py .`):

| Module | Line | Branch |
|---|---|---|
| strata-healer | 95.6% | 84.5% |
| inventory-service | 91.7% | 98.1% |
| order-service | 90.7% | 94.0% |
| **Total** | **94.9%** | **86.4%** |

Classes in this area:

| Class | Lines | Branches |
|---|---|---|
| PrometheusRecoveryVerifier | 51/52 | 30/32 |
| PrometheusClient | 35/35 | 13/13 |
| FreshConnectionDockerHttpClient | 8/8 | 2/2 |
| VerificationConfig, PrometheusException, Sample | 10/10 | none |

`PrometheusClient` had the weakest branch coverage (6 of 10). The missed branches
were odd responses from Prometheus: an empty body, and a non-success status
with no `error` field. Tests for these now cover every branch (13 of 13 after the
D-4 fix added a few). Writing them also found D-4.

## Summary

26 of 27 checks pass. The alerts fire at the thresholds and times we designed
and stay quiet while a service or the gateway is still starting. The verifier only
reports recovery after 3 healthy checks in a row. On the live stack a crashed
service went from `docker stop` to verified recovery in about 36s. The one failure
(D-1) is a proxy rule that is looser than we wrote down. It can't run a command,
but it should still be closed.

Limits: alert timing depends on the 5s scrape interval, so live numbers vary by a
few seconds between runs. The live checks above were run once; repeated runs are
in the benchmark (#46).
