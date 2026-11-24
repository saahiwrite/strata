#!/usr/bin/env bash
#
# demo.sh: runs STRATA's two use cases on the real Compose stack, start to finish.
#
#   1. S01  order-service crashes  -> STRATA restarts it on its own       -> incident RESOLVED
#   2. S14  bad deployment         -> STRATA proposes a rollback at L2,
#                                     waits for you to Approve it on the dashboard,
#                                     rolls back                          -> incident RESOLVED
#
# Usage:  ./scenarios/demo.sh            (from the repo root or anywhere)
#
# Starts the stack first if it isn't running (builds the jars if they are missing). Each step
# says what it is waiting for and stops with a clear message if it times out.
#
# Settings (environment variables):
#   HEALER_URL       http://localhost:9000   the healer (API and dashboard)
#   PROMETHEUS_URL   http://localhost:9090
#   ALERTMANAGER_URL http://localhost:9093
#   STRATA_GATEWAY   http://localhost:8080   Nginx, used by inject.sh
#   APPROVAL_WAIT    600                     seconds to wait for you to click Approve
#
# Needs: docker (with Compose v2), curl, python3 (to read the healer's JSON).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
INJECT="$REPO_ROOT/scenarios/inject.sh"
HEALER_URL="${HEALER_URL:-http://localhost:9000}"
GATEWAY="${STRATA_GATEWAY:-http://localhost:8080}"
PROMETHEUS_URL="${PROMETHEUS_URL:-http://localhost:9090}"
ALERTMANAGER_URL="${ALERTMANAGER_URL:-http://localhost:9093}"
APPROVAL_WAIT="${APPROVAL_WAIT:-600}"

bold() { printf '\n\033[1m%s\033[0m\n' "$*"; }
info() { printf '  %s\n' "$*"; }
die() {
    printf '\n\033[31mdemo.sh: %s\033[0m\n' "$1" >&2
    [[ -n "${2:-}" ]] && printf '  %s\n' "$2" >&2
    exit 1
}

compose() {
    docker compose -f "$REPO_ROOT/docker-compose.yml" --project-directory "$REPO_ROOT" "$@"
}

# json <python expression over `d`>: reads JSON on stdin and prints the expression.
json() {
    python3 -c "import sys, json; d = json.load(sys.stdin); print($1)"
}

healer() {
    curl -sf --max-time 5 "$HEALER_URL$1"
}

# wait_until <seconds> <description> <command...>: retries the command every 3 s.
wait_until() {
    local timeout="$1" what="$2" start
    shift 2
    start=$(date +%s)
    info "waiting for $what (up to ${timeout}s)..."
    until "$@" >/dev/null 2>&1; do
        if (($(date +%s) - start >= timeout)); then
            return 1
        fi
        sleep 3
    done
    info "ok: $what ($(($(date +%s) - start))s)"
}

latest_incident_id() {
    healer /api/incidents | json "max([i['id'] for i in d], default=0)"
}

# new_incident <after-id> <alert>: prints the id of the first incident after <after-id> for that alert.
new_incident() {
    local id
    id="$(healer /api/incidents | json "min([i['id'] for i in d if i['id'] > $1 and i['alertName'] == '$2'], default='')")"
    [[ -n "$id" ]] && echo "$id"
}

incident_status() {
    healer "/api/incidents/$1" | json "d['incident']['status']"
}

incident_closed() {
    [[ "$(incident_status "$1")" != "OPEN" ]]
}

pending_decision() {
    local id
    id="$(healer "/api/incidents/$1" | json "next((x['id'] for x in d['decisions'] if x['outcome'] == 'AWAITING_APPROVAL'), '')")"
    [[ -n "$id" ]] && echo "$id"
}

print_audit() {
    healer "/api/incidents/$1" | python3 -c "
import sys, json
d = json.load(sys.stdin)
for e in d['audit']:
    print('    {:>4}  {:<16} {}'.format(e['seq'], e['eventType'], e['payload'][:110]))
"
}

order_service_healthy() {
    curl -sf --max-time 3 "$GATEWAY/order-service/actuator/health" >/dev/null
}

order_service_up_in_prometheus() {
    curl -sf --max-time 5 -G "$PROMETHEUS_URL/api/v1/query" --data-urlencode 'query=up{job="order-service"}' \
        | json "1 if d['data']['result'] and all(r['value'][1] == '1' for r in d['data']['result']) else exit(1)"
}

# no_alert <alertname>: true when neither Prometheus nor Alertmanager has that alert active for
# order-service. Alertmanager only notifies the healer when an alert starts, so injecting a
# failure while the previous alert is still active would never open an incident.
no_alert() {
    curl -sf --max-time 5 "$PROMETHEUS_URL/api/v1/alerts" \
        | json "exit(1) if any(a['labels'].get('alertname') == '$1' and a['labels'].get('service') == 'order-service' for a in d['data']['alerts']) else 1" \
        && curl -sf --max-time 5 "$ALERTMANAGER_URL/api/v2/alerts?active=true" \
        | json "exit(1) if any(a['labels'].get('alertname') == '$1' and a['labels'].get('service') == 'order-service' for a in d) else 1"
}

order_service_image() {
    local c
    c="$(compose ps -q order-service | head -n 1)"
    [[ -n "$c" ]] && docker inspect --format '{{.Config.Image}}' "$c"
}

open_incident_for_order_service() {
    healer '/api/incidents?status=OPEN' | json "next((str(i['id']) for i in d if i['service'] == 'order-service'), '')"
}

# --- 0. Preflight and stack -----------------------------------------------------------------

bold "STRATA demo: S01 (crash, automatic restart) and S14 (bad deploy, rollback with approval)"

for tool in docker curl python3; do
    command -v "$tool" >/dev/null || die "$tool is not installed" "demo.sh needs docker, curl and python3."
done
docker info >/dev/null 2>&1 || die "Docker is not running" "Start Docker Desktop (or the Docker daemon) and run demo.sh again."

if ! healer /actuator/health >/dev/null 2>&1; then
    bold "Starting the stack (docker compose up --build)"
    [[ -f "$REPO_ROOT/.env" ]] || { cp "$REPO_ROOT/.env.example" "$REPO_ROOT/.env"; info "created .env from .env.example"; }
    if [[ ! -f "$REPO_ROOT/target-services/order-service/target/order-service.jar" \
        || ! -f "$REPO_ROOT/target-services/inventory-service/target/inventory-service.jar" ]] \
        || ! ls "$REPO_ROOT"/strata-healer/target/*.jar >/dev/null 2>&1; then
        command -v mvn >/dev/null || die "the jars are not built and Maven is not installed" "Install Maven 3.9+ or run: mvn clean package -DskipTests"
        info "building the jars (mvn clean package -DskipTests)..."
        (cd "$REPO_ROOT" && mvn -q -B clean package -DskipTests) || die "the Maven build failed"
    fi
    compose up --build -d || die "docker compose up failed" "See the output above, or: docker compose logs"
fi

bold "Waiting for the stack"
wait_until 180 "the healer at $HEALER_URL" healer /actuator/health \
    || die "the healer did not come up" "Check: docker compose logs strata-healer"
if ! order_service_healthy; then
    # e.g. left down by an earlier run that ended ESCALATED
    info "order-service is not answering; starting it"
    compose up -d --no-deps --no-build order-service >/dev/null 2>&1 || true
fi
wait_until 120 "order-service through Nginx" order_service_healthy \
    || die "order-service is not reachable through Nginx" "Check: docker compose ps"
wait_until 120 "Prometheus to see order-service up" order_service_up_in_prometheus \
    || die "Prometheus does not see order-service" "Open $PROMETHEUS_URL/targets"

if [[ "$(order_service_image)" != "strata/order-service:1.0" ]]; then
    info "order-service runs $(order_service_image); putting 1.0 back first"
    "$INJECT" deploy order-service 1.0 >/dev/null || die "could not deploy order-service 1.0"
    wait_until 120 "Prometheus to see order-service up" order_service_up_in_prometheus || die "order-service did not come back"
fi

open_id="$(open_incident_for_order_service)"
[[ -z "$open_id" ]] || die "incident #$open_id for order-service is still open" \
    "STRATA adds new alerts to an open incident. Close it first (approve or deny it on $HEALER_URL/dashboard), then run demo.sh again."

"$INJECT" reset all >/dev/null 2>&1 || true

# The healer won't act on a container it acted on in the last 60 s (its cooldown guard), so a
# second run straight after the first would end ESCALATED. Wait out the cooldown if needed.
since_last="$(healer /api/incidents | python3 -c "
import sys, json, datetime
d = [i for i in json.load(sys.stdin) if i['service'] == 'order-service' and i['closedAt']]
if not d:
    print(9999)
else:
    t = max(datetime.datetime.fromisoformat(i['closedAt'].replace('Z', '+00:00')) for i in d)
    print(int((datetime.datetime.now(datetime.timezone.utc) - t).total_seconds()))
")"
if ((since_last < 75)); then
    info "STRATA acted on order-service ${since_last}s ago; waiting $((75 - since_last))s for its cooldown"
    sleep $((75 - since_last))
fi

wait_until 240 "no ServiceDown alert left from an earlier run" no_alert ServiceDown \
    || die "a ServiceDown alert for order-service is still active" "Wait a few minutes and run demo.sh again."

# --- 1. S01: crash -> automatic restart -------------------------------------------------------

bold "1/2  S01: order-service crashes; STRATA should restart it with no human input"
before="$(latest_incident_id)"
"$INJECT" crash order-service >/dev/null || die "could not crash order-service"
info "crashed order-service at $(date +%T)"

wait_until 120 "a ServiceDown incident" new_incident "$before" ServiceDown \
    || die "no ServiceDown incident after 2 minutes" "Check Prometheus alerts ($PROMETHEUS_URL/alerts) and: docker compose logs strata-healer"
crash_id="$(new_incident "$before" ServiceDown)"
info "incident #$crash_id opened"
wait_until 180 "incident #$crash_id to close" incident_closed "$crash_id" \
    || die "incident #$crash_id is still open after 3 minutes" "See $HEALER_URL/dashboard/incidents/$crash_id"
status="$(incident_status "$crash_id")"
info "audit trail of incident #$crash_id:"
print_audit "$crash_id"
[[ "$status" == "RESOLVED" ]] || die "incident #$crash_id ended $status, expected RESOLVED" "See $HEALER_URL/dashboard/incidents/$crash_id"
info "S01 passed: crash detected, restarted, verified, RESOLVED"

wait_until 120 "Prometheus to see order-service up again" order_service_up_in_prometheus \
    || die "order-service did not come back after the restart"

# --- 2. S14: bad deployment -> rollback with approval -----------------------------------------

wait_until 240 "no HighErrorRate alert left from an earlier run" no_alert HighErrorRate \
    || die "a HighErrorRate alert for order-service is still active" "Wait a few minutes and run demo.sh again."

bold "2/2  S14: a broken release of order-service is deployed; STRATA should ask to roll it back"
before="$(latest_incident_id)"
s14_log="$(mktemp)"
"$INJECT" scenario S14 >"$s14_log" 2>&1 &
s14_pid=$!
trap 'kill "$s14_pid" 2>/dev/null || true; rm -f "$s14_log"' EXIT
info "deploying strata/order-service:1.1-broken and sending steady traffic (about 70 s)..."

wait_until 180 "a HighErrorRate incident" new_incident "$before" HighErrorRate \
    || { cat "$s14_log"; die "no HighErrorRate incident after 3 minutes" "Check $PROMETHEUS_URL/alerts"; }
deploy_id="$(new_incident "$before" HighErrorRate)"
info "incident #$deploy_id opened"
wait_until 60 "a decision waiting for approval" pending_decision "$deploy_id" \
    || die "incident #$deploy_id has no decision waiting for approval" \
        "It ended $(incident_status "$deploy_id"); see $HEALER_URL/dashboard/incidents/$deploy_id"
decision="$(pending_decision "$deploy_id")"

bold "Your turn: open $HEALER_URL/dashboard and click Approve on the ROLLBACK"
info "(or: curl -X POST $HEALER_URL/api/approvals/$decision/approve)"
wait_until "$APPROVAL_WAIT" "your approval of decision #$decision" incident_closed "$deploy_id" \
    || die "incident #$deploy_id was not approved within ${APPROVAL_WAIT}s" "Approve it on $HEALER_URL/dashboard, or raise APPROVAL_WAIT."
wait "$s14_pid" 2>/dev/null || true

status="$(incident_status "$deploy_id")"
info "audit trail of incident #$deploy_id:"
print_audit "$deploy_id"
[[ "$status" == "RESOLVED" ]] || die "incident #$deploy_id ended $status, expected RESOLVED" "See $HEALER_URL/dashboard/incidents/$deploy_id"
image="$(docker inspect --format '{{.Config.Image}}' strata-order-service-1 2>/dev/null || true)"
[[ "$image" == "strata/order-service:1.0" ]] || die "order-service runs '$image' after the rollback, expected strata/order-service:1.0"
info "S14 passed: bad deploy detected, rollback approved, order-service back on 1.0, RESOLVED"

bold "Both use cases passed. Incidents #$crash_id and #$deploy_id are on $HEALER_URL/dashboard"
