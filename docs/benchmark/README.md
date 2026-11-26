# Benchmark: partial run, S01-S12, full STRATA (#46)

This is the first benchmark run. It covers the 12 scenarios that `inject.sh` can run today (S01-S12 in `scenarios/README.md`), with **full STRATA** only. The rules-only and no-healer modes need a mode switch in the healer (see "Not done yet" below).

- Date: 2026-11-26, on `main` at `4b4991f` (after #75)
- Machine: MacBook, Colima VM with 2 CPUs and 3 GB RAM, the Compose stack from a clean start (`docker compose down -v` first)
- LLM diagnosis: off (the `llm` profile was not started), so alerts with no matching rule go straight to a human
- Raw data: [`results.csv`](results.csv), one row per incident

## How to run it

```bash
docker compose up -d --build        # stack running, as in the README
python3 scenarios/benchmark.py      # S01-S12, about 25 minutes
python3 scenarios/benchmark.py S01 S08   # or just some scenarios
```

For each scenario the script puts the stack back to normal (switches off, extra replicas removed, no alerts, healer cooldown over), injects it with `inject.sh`, and watches `/api/incidents` until every incident is closed. While it runs it sends about 4 requests a second to each service through Nginx, like real users. Without that, the scenarios that send one burst of requests finish before Prometheus sees the error counter twice, and no error-rate alert can fire.

**Detect** is the time from injection to the incident opening. **Close** is the time from injection to the incident closing (RESOLVED after the verifier saw 3 healthy checks, or ESCALATED to a human).

## Results

| Scenario | What we injected | Incidents | Action | Final status | Detect | Close |
|---|---|---|---|---|---|---|
| S01 | order-service crashes | 1 | RESTART (L0) | RESOLVED | 13s | 38s |
| S02 | inventory-service crashes | 1 | RESTART (L0) | RESOLVED | 20s | 35s |
| S03 | both crash at once | 2 | RESTART x2 (L0) | RESOLVED | 14s | 40s |
| S04 | order-service crash loop (3 crashes, 60s apart) | 2 | RESTART, then cooldown | ESCALATED | 14s | 79s |
| S05 | order-service fails every request | 1 | none (no rule) | ESCALATED | 40s | 45s |
| S06 | inventory-service fails 30% | 0 | - | no incident (see notes) | - | - |
| S07 | order-service fails 2% (noise) | 0 | - | no incident (correct) | - | - |
| S08 | order-service 2s slow | 1 | SCALE_OUT (L0) | RESOLVED | 39s | 49s |
| S09 | inventory-service 5s slow | 1 | SCALE_OUT (L0) | RESOLVED | 42s | 59s |
| S10 | order-service slow and failing 50% | 2 | SCALE_OUT, then none | ESCALATED | 33s | 146s |
| S11 | order-service memory grows until OOM | 1 | RESTART (L0) | RESOLVED | 34s | 69s |
| S12 | inventory-service memory grows | 1 | none (no rule) | ESCALATED | 55s | 62s |

| Measure | Value |
|---|---|
| Auto-resolution rate (fault scenarios that ended RESOLVED with no human) | 6/11 (55%) |
| MTTR, injection to RESOLVED, auto-resolved scenarios | 48s |
| Mean time to detect | 34s |
| False alarms (S07, where no incident is right) | 0 |
| False fixes (action ran, verifier said not recovered) | 1 (S10 scale out) |
| Approvals asked | 0 of 13 incidents (no L2 action in S01-S12) |
| Prompt-injection block rate | not measured (S15-S16 need #72) |

## What the numbers say

- **Crashes heal on their own.** S01-S03 and S11 ended RESOLVED in 35-69s with no human. About 15s of that is detection (`ServiceDown` waits 10s), and most of the rest is Spring Boot starting again.
- **The cooldown does its job in S04.** The first crash was restarted. The second came 60s later, while the cooldown was still on, so STRATA stopped and handed it to a human instead of restarting in a loop.
- **Slow responses are fixed by scaling out.** S08 and S09 ended RESOLVED in about 50-60s.
- **Error-rate and memory alerts go to a human.** S05, S10 (the error part) and S12 were detected but ended ESCALATED, because `rules.yaml` only has a rule for `HighErrorRate` right after a deploy (rollback), and no rule for `HighMemory`. This is the safe default. The catalogue expected a restart for these, so adding restart rules would raise auto-resolution to about 9/11. That's a decision for the healer rules owner (Madhuri).
- **S10 shows a false fix.** STRATA scaled out for the slow responses, but half the requests kept failing, so the verifier said "not recovered" and the incident was escalated. That's the right outcome for a wrong fix. It also means the restart trust record was not touched, only scale out got a failure.
- **No false alarm on noise (S07).** 2% errors stayed below the 20% threshold.

## Notes and problems found

- **S06 missed in the full run but works alone.** In the full run S06 (30% errors) ended with no incident. Run on its own right after (`results-s06-rerun.csv`), `HighErrorRate` fired at 39s and the incident was escalated like S05. A 30% error rate is close to the 20% threshold and the alert needs 30s above it, so the result depends on how quickly the rate builds up. Next run: give S06 a longer watch window, or a steadier load.
- **The benchmark found a real bug.** In the first try (Nov 24), the first restart of almost every incident failed with `NoHttpResponseException: docker-proxy:2375 failed to respond`, and services stayed down about 6 minutes until the alert repeated. Fixed in #75. All numbers here are after the fix.
- Each scenario was run once, so these are single measurements, not averages.

## Not done yet

- **Modes.** "Manual fix" and "rules only (no risk gate)" need a `strata.mode` setting in `HealingOrchestrator`. That code belongs to the orchestrator owner, so it should be agreed with Madhuri first.
- **S13-S18.** They need the Phase 3 injections from #72 (traffic spike, bad deploy, malicious log lines). S18 (Nginx down) works by hand since #74.
- **Repeats.** Three runs per scenario would give an average and a spread.
