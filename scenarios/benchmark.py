#!/usr/bin/env python3
"""benchmark.py: run failure scenarios against the live stack and record what STRATA did (#46).

For each scenario it:
  1. puts the stack back to normal (switches off, extra replicas removed, both
     services up, no alerts, healer cooldown over),
  2. injects the scenario with scenarios/inject.sh,
  3. watches the healer's incidents until they are closed and things are quiet,
  4. records, per incident: alert, action, autonomy level, outcome, and the time
     from injection to the incident opening (detection) and closing (recovery).

Results go to docs/benchmark/results.csv (one row per incident, or one "no incident"
row) and a summary table is printed in Markdown.

Usage (stack running, from the repo root):
    python3 scenarios/benchmark.py                 # S01-S12
    python3 scenarios/benchmark.py S01 S05 S08     # just these
    python3 scenarios/benchmark.py --mode full     # label for the run (default: full)

Only the "full STRATA" mode exists today. The rules-only and no-healer modes need a
switch in the healer (see #46), so --mode is just written into the CSV for now.

While it runs it sends a few requests per second to both services through Nginx,
so error-rate and latency alerts see steady traffic like they would with real users.

Needs: python3 (stdlib only), docker CLI (same daemon as the stack), curl (used by inject.sh).
Settings: HEALER_URL (http://localhost:9000), PROMETHEUS_URL (http://localhost:9090),
STRATA_GATEWAY (http://localhost:8080).
"""
import argparse
import csv
import json
import os
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
from datetime import datetime

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
INJECT = os.path.join(REPO, "scenarios", "inject.sh")
HEALER = os.environ.get("HEALER_URL", "http://localhost:9000")
GATEWAY = os.environ.get("STRATA_GATEWAY", "http://localhost:8080")
PROMETHEUS = os.environ.get("PROMETHEUS_URL", "http://localhost:9090")
SERVICES = ["order-service", "inventory-service"]

# Phase 2 scenarios (inject.sh can run these today). S07 is the only one where
# no incident is the right answer.
SCENARIOS = [f"S{i:02d}" for i in range(1, 13)]
EXPECT_NO_INCIDENT = {"S07"}

COOLDOWN_S = 65          # healer cooldown is 60s per target
QUIET_AFTER_S = 45       # after injection ends, wait this long with nothing open
MAX_SCENARIO_S = 480     # give up on a scenario after this
POLL_S = 3

# Steady background traffic, like real users, for the whole run. Without it the
# scenarios that send a burst of requests (S05-S07, S10) finish before Prometheus
# has scraped the new error counter twice, so rate() sees no errors at all.
BACKGROUND_PATHS = ["/order-service/orders", "/inventory-service/inventory"]
BACKGROUND_RPS = 4       # per path


def log(msg):
    print(f"[{datetime.now():%H:%M:%S}] {msg}", flush=True)


def get_json(url, timeout=10):
    with urllib.request.urlopen(url, timeout=timeout) as resp:
        return json.load(resp)


def prom(query):
    url = f"{PROMETHEUS}/api/v1/query?" + urllib.parse.urlencode({"query": query})
    return get_json(url)["data"]["result"]


def active_alerts():
    try:
        return [a for a in get_json(f"{PROMETHEUS}/api/v1/alerts")["data"]["alerts"]
                if a["labels"].get("service") in SERVICES]
    except Exception:
        return []


def incidents():
    # The healer can be slow for a moment while it restarts a container;
    # retry a few times instead of losing the whole run.
    for attempt in range(5):
        try:
            return get_json(f"{HEALER}/api/incidents")
        except Exception:
            if attempt == 4:
                raise
            time.sleep(2)


def incident(incident_id):
    for attempt in range(5):
        try:
            return get_json(f"{HEALER}/api/incidents/{incident_id}")
        except Exception:
            if attempt == 4:
                raise
            time.sleep(2)


def parse_time(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp() if value else None


def docker(*args):
    return subprocess.run(["docker", *args], capture_output=True, text=True)


def inject(*args, background=False):
    cmd = [INJECT, *args]
    if background:
        return subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def wait_for(what, check, timeout):
    start = time.time()
    while time.time() - start < timeout:
        try:
            if check():
                return True
        except Exception:
            pass
        time.sleep(POLL_S)
    log(f"  gave up waiting for {what} after {timeout}s")
    return False


def background_traffic(stop):
    """Send BACKGROUND_RPS requests per second to each service until stop is set."""
    while not stop.is_set():
        for path in BACKGROUND_PATHS:
            try:
                urllib.request.urlopen(GATEWAY + path, timeout=2).read()
            except Exception:
                pass  # errors and timeouts are what the scenarios cause
        stop.wait(1 / BACKGROUND_RPS)


def services_up():
    result = prom('up{job=~"order-service|inventory-service"}')
    up = {r["metric"]["service"]: r["value"][1] for r in result}
    return all(up.get(s) == "1" for s in SERVICES)


def reset_stack():
    """Put everything back to normal before a scenario."""
    # Extra replicas from a scale out (strata-<service>-2, -3, ...) are removed.
    names = docker("ps", "-a", "--filter", "label=com.docker.compose.project=strata",
                   "--format", "{{.Names}}").stdout.split()
    for name in names:
        for service in SERVICES:
            if name.startswith(f"strata-{service}-") and not name.endswith("-1"):
                docker("rm", "-f", name)
    # A service the healer gave up on stays down; start it again.
    for service in SERVICES:
        docker("start", f"strata-{service}-1")
    wait_for("both services up", services_up, 180)
    inject("reset", "all")
    wait_for("no alerts", lambda: not active_alerts(), 240)
    # Let the healer's cooldown run out, so the next action isn't skipped.
    closed = [parse_time(i.get("closedAt")) for i in incidents() if i.get("closedAt")]
    if closed:
        wait = COOLDOWN_S - (time.time() - max(closed))
        if wait > 0:
            log(f"  waiting {wait:.0f}s for the healer cooldown")
            time.sleep(wait)


def describe(inc, t0):
    """One CSV row for an incident."""
    details = incident(inc["id"])
    decisions = details.get("decisions") or []
    last = decisions[-1] if decisions else {}
    opened = parse_time(inc.get("openedAt"))
    closed = parse_time(inc.get("closedAt"))
    return {
        "incident": inc["id"],
        "alert": inc["alertName"],
        "service": inc["service"],
        "action": last.get("action", ""),
        "level": last.get("autonomyLevel", ""),
        "decision": last.get("outcome", ""),
        "status": inc["status"],
        "detect_s": f"{opened - t0:.0f}" if opened else "",
        "close_s": f"{closed - t0:.0f}" if closed else "",
    }


def run_scenario(sid):
    log(f"{sid}: resetting the stack")
    reset_stack()
    before = max([i["id"] for i in incidents()], default=0)
    t0 = time.time()
    log(f"{sid}: injecting")
    proc = inject("scenario", sid, background=True)

    quiet_since = None
    while time.time() - t0 < MAX_SCENARIO_S:
        time.sleep(POLL_S)
        new = [i for i in incidents() if i["id"] > before]
        # Busy while injecting, while an incident is open, or while an alert is
        # building up with no incident yet. Once every incident is closed (even
        # ESCALATED, with the fault still there) the scenario is over.
        busy = (proc.poll() is None
                or any(i["status"] == "OPEN" for i in new)
                or (not new and active_alerts()))
        if busy:
            quiet_since = None
            continue
        quiet_since = quiet_since or time.time()
        if time.time() - quiet_since >= QUIET_AFTER_S:
            break
    else:
        log(f"{sid}: still busy after {MAX_SCENARIO_S}s, recording what happened so far")
    if proc.poll() is None:
        proc.kill()

    new = sorted((i for i in incidents() if i["id"] > before), key=lambda i: i["id"])
    rows = [describe(i, t0) for i in new]
    if not rows:
        rows = [{"incident": "", "alert": "", "service": "", "action": "", "level": "",
                 "decision": "", "status": "NO_INCIDENT", "detect_s": "", "close_s": ""}]
    for row in rows:
        row["scenario"] = sid
        log(f"  {row['status']:<11} {row['alert'] or '-':<14} {row['action'] or '-':<10} "
            f"{row['level'] or '-':<12} detect {row['detect_s'] or '-'}s, close {row['close_s'] or '-'}s")
    return rows


def summarize(rows):
    by_scenario = {}
    for row in rows:
        by_scenario.setdefault(row["scenario"], []).append(row)

    faulty = [s for s in by_scenario if s not in EXPECT_NO_INCIDENT]
    auto = [s for s in faulty
            if by_scenario[s][0]["status"] != "NO_INCIDENT"
            and by_scenario[s][-1]["status"] == "RESOLVED"]
    resolved = [r for r in rows if r["status"] == "RESOLVED" and r["close_s"]]
    detected = [r for r in rows if r["detect_s"]]
    first_resolve = [float(by_scenario[s][-1]["close_s"]) for s in auto]
    noise = [s for s in by_scenario if s in EXPECT_NO_INCIDENT and by_scenario[s][0]["status"] != "NO_INCIDENT"]
    approvals = [r for r in rows if r["level"].startswith("L2")]
    incident_rows = [r for r in rows if r["status"] != "NO_INCIDENT"]

    def mean(values):
        return f"{sum(values) / len(values):.0f}s" if values else "n/a"

    print("\n| Scenario | Incidents | Alerts | Actions | Levels | Final status | Detect | Close |")
    print("|---|---|---|---|---|---|---|---|")
    for sid, srows in by_scenario.items():
        real = [r for r in srows if r["status"] != "NO_INCIDENT"]
        join = lambda key: ", ".join(r[key] for r in real if r[key]) or "-"
        print(f"| {sid} | {len(real)} | {join('alert')} | {join('action')} | {join('level')} | "
              f"{srows[-1]['status']} | {srows[0]['detect_s'] or '-'}s | {srows[-1]['close_s'] or '-'}s |")

    print("\n| Measure | Value |")
    print("|---|---|")
    print(f"| Scenarios run | {len(by_scenario)} |")
    print(f"| Auto-resolution rate (fault scenarios ending RESOLVED, no human) | {len(auto)}/{len(faulty)} |")
    print(f"| MTTR, injection to RESOLVED (auto-resolved scenarios) | {mean(first_resolve)} |")
    print(f"| Mean time to detect (injection to incident) | {mean([float(r['detect_s']) for r in detected])} |")
    print(f"| Incidents resolved / total | {len(resolved)}/{len(incident_rows)} |")
    print(f"| False alarms (incident where none was expected, S07) | {len(noise)} |")
    print(f"| Approvals asked per incident | {len(approvals)}/{len(incident_rows)} |")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("scenarios", nargs="*", default=SCENARIOS)
    parser.add_argument("--mode", default="full")
    parser.add_argument("--out", default=os.path.join(REPO, "docs", "benchmark", "results.csv"))
    args = parser.parse_args()

    try:
        incidents()
    except Exception as e:
        sys.exit(f"benchmark.py: can't reach the healer at {HEALER} ({e}). Start the stack first.")

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    fields = ["mode", "scenario", "incident", "alert", "service", "action", "level",
              "decision", "status", "detect_s", "close_s"]

    def save(rows):
        # written after every scenario, so a crash part way keeps what was measured
        with open(args.out, "w", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=fields)
            writer.writeheader()
            writer.writerows(rows)

    stop = threading.Event()
    threading.Thread(target=background_traffic, args=(stop,), daemon=True).start()
    rows = []
    try:
        for sid in [s.upper() for s in args.scenarios]:
            for row in run_scenario(sid):
                row["mode"] = args.mode
                rows.append(row)
            save(rows)
    finally:
        stop.set()
    log(f"wrote {args.out}")
    summarize(rows)


if __name__ == "__main__":
    main()
