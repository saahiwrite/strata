#!/usr/bin/env bash
#
# inject.sh: flip the failure switches on the STRATA target services.
#
# Usage:
#   ./scenarios/inject.sh crash   <service>
#   ./scenarios/inject.sh error   <service> [rate]                 (default 1.0)
#   ./scenarios/inject.sh slow    <service> [delayMs]              (default 2000)
#   ./scenarios/inject.sh memory  <service> [mbPerSecond] [maxMb]  (default 10 512)
#   ./scenarios/inject.sh reset   <service|all>
#   ./scenarios/inject.sh status  <service|all>
#   ./scenarios/inject.sh traffic <service> [requests]             (default 200)
#   ./scenarios/inject.sh scenario <ID>                            (e.g. S01, see scenarios/README.md)
#   ./scenarios/inject.sh list
#
#   <service> is order-service or inventory-service.
#
# By default requests go through Nginx (http://localhost:8080). Override with:
#   STRATA_GATEWAY=http://host:8080          base URL of the Nginx entry point
#   ORDER_SERVICE_URL=http://localhost:8081  talk to order-service directly
#   INVENTORY_SERVICE_URL=...                talk to inventory-service directly

set -euo pipefail

GATEWAY="${STRATA_GATEWAY:-http://localhost:8080}"
ORDER_URL="${ORDER_SERVICE_URL:-$GATEWAY/order-service}"
INVENTORY_URL="${INVENTORY_SERVICE_URL:-$GATEWAY/inventory-service}"
SERVICES=(order-service inventory-service)

die() {
    echo "inject.sh: $*" >&2
    exit 1
}

usage() {
    sed -n '3,21p' "$0" | sed 's/^# \{0,1\}//'
    exit "${1:-0}"
}

base_url() {
    case "$1" in
        order-service)     echo "$ORDER_URL" ;;
        inventory-service) echo "$INVENTORY_URL" ;;
        *) die "unknown service '$1' (expected: ${SERVICES[*]})" ;;
    esac
}

# Business endpoint used to generate traffic, so error-rate and latency metrics move.
traffic_path() {
    case "$1" in
        order-service)     echo "/orders" ;;
        inventory-service) echo "/inventory" ;;
    esac
}

is_number() {
    [[ "$1" =~ ^[0-9]+([.][0-9]+)?$ ]]
}

post() {
    local service="$1" path="$2"
    local url
    url="$(base_url "$service")$path"
    echo "POST $url" >&2
    local body status
    body="$(curl -sS -X POST -w $'\n%{http_code}' "$url")" || die "could not reach $service at $url"
    status="${body##*$'\n'}"
    body="${body%$'\n'*}"
    echo "$body"
    [[ "$status" == 2* ]] || die "$service answered HTTP $status"
}

for_each_service() {
    local target="$1"; shift
    if [[ "$target" == "all" ]]; then
        local s
        for s in "${SERVICES[@]}"; do
            "$@" "$s"
        done
    else
        "$@" "$target"
    fi
}

cmd_crash() {
    post "$1" /chaos/crash
}

cmd_error() {
    local rate="${2:-1.0}"
    is_number "$rate" || die "rate must be a number between 0 and 1"
    post "$1" "/chaos/error?rate=$rate"
}

cmd_slow() {
    local delay="${2:-2000}"
    [[ "$delay" =~ ^[0-9]+$ ]] || die "delayMs must be a whole number"
    post "$1" "/chaos/slow?delayMs=$delay"
}

cmd_memory() {
    local rate="${2:-10}" cap="${3:-512}"
    [[ "$rate" =~ ^[0-9]+$ && "$cap" =~ ^[0-9]+$ ]] || die "mbPerSecond and maxMb must be whole numbers"
    post "$1" "/chaos/memory?mbPerSecond=$rate&maxMb=$cap"
}

cmd_reset() {
    post "$1" /chaos/reset
}

cmd_status() {
    local url
    url="$(base_url "$1")/chaos"
    printf '%s: ' "$1"
    curl -sS --max-time 5 "$url" || printf 'unreachable'
    echo
}

cmd_traffic() {
    local service="$1" count="${2:-200}"
    [[ "$count" =~ ^[0-9]+$ ]] || die "requests must be a whole number"
    local url
    url="$(base_url "$service")$(traffic_path "$service")"
    echo "Sending $count requests to $url" >&2
    seq "$count" | xargs -P 8 -I{} curl -s -o /dev/null -w '%{http_code}\n' --max-time 30 "$url" \
        | sort | uniq -c | awk '{printf "  HTTP %s: %s\n", $2, $1}'
}

# Phase 2 scenarios that the switches can inject today. IDs match scenarios/README.md.
cmd_scenario() {
    local id
    id="$(echo "${1:-}" | tr '[:lower:]' '[:upper:]')"
    case "$id" in
        S01) cmd_crash order-service ;;
        S02) cmd_crash inventory-service ;;
        S03) cmd_crash order-service; cmd_crash inventory-service ;;
        S04)
            # Crash, wait for STRATA to restart it, crash again: exercises the cooldown guard.
            local wait="${S04_WAIT_SECONDS:-60}" i
            for i in 1 2 3; do
                echo "Crash $i of 3" >&2
                cmd_crash order-service || true
                if [[ $i -lt 3 ]]; then sleep "$wait"; fi
            done
            ;;
        S05) cmd_error order-service 1.0; cmd_traffic order-service 300 ;;
        S06) cmd_error inventory-service 0.3; cmd_traffic inventory-service 300 ;;
        S07) cmd_error order-service 0.02; cmd_traffic order-service 300 ;;
        S08) cmd_slow order-service 2000; cmd_traffic order-service 40 ;;
        S09) cmd_slow inventory-service 5000; cmd_traffic inventory-service 24 ;;
        S10) cmd_slow order-service 1500; cmd_error order-service 0.5; cmd_traffic order-service 100 ;;
        S11) cmd_memory order-service 20 4096 ;;
        S12) cmd_memory inventory-service 5 256 ;;
        S1[3-8])
            die "$id needs a Phase 3 feature and cannot be injected yet (see scenarios/README.md)" ;;
        "") die "scenario needs an ID, e.g. S01" ;;
        *) die "unknown scenario '$1' (see ./scenarios/inject.sh list)" ;;
    esac
}

cmd_list() {
    local readme
    readme="$(dirname "$0")/README.md"
    [[ -f "$readme" ]] || die "catalogue not found at $readme"
    grep -E '^\| S[0-9]{2} ' "$readme" | awk -F'|' '{gsub(/^ +| +$/, "", $2); gsub(/^ +| +$/, "", $3); gsub(/^ +| +$/, "", $9); printf "%-4s %-48s %s\n", $2, $3, $9}'
}

main() {
    [[ $# -ge 1 ]] || usage 1
    local cmd="$1"; shift
    case "$cmd" in
        crash|error|slow|memory|traffic)
            [[ $# -ge 1 ]] || die "$cmd needs a service (${SERVICES[*]})"
            base_url "$1" >/dev/null
            "cmd_$cmd" "$@"
            ;;
        reset|status)
            for_each_service "${1:-all}" "cmd_$cmd"
            ;;
        scenario) cmd_scenario "$@" ;;
        list) cmd_list ;;
        -h|--help|help) usage 0 ;;
        *) echo "inject.sh: unknown command '$cmd'" >&2; usage 1 ;;
    esac
}

main "$@"
