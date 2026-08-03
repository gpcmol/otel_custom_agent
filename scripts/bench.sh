#!/usr/bin/env bash
set -euo pipefail
# bench.sh — bash-orchestrated, k6-driven overhead benchmark for the OTel custom agent.
#
# Starts the OTLP stub + car-app (javaagent enabled or disabled) as background processes,
# fires a 10k k6 pre-warmup + 1M k6 measurement POST /cars per mode, computes throughput
# overhead, then a verify run checks enrichment attributes in telemetry.log. Results are
# written as markdown to build/benchmark-results/runtime-state-benchmark-<ts>.md.
#
# The javaagent is always attached (-javaagent). The only variable between runs is the
# OTEL_CUSTOM_AGENT_CONFIG env var: set for "enabled", empty for "disabled". This isolates
# the enrichment overhead as the sole difference (RuntimeState.enabled vs .disabled).
#
# Usage: bench.sh [--mode all|enabled|disabled|verify]
#                 [--warmup N] [--measurement N] [--vus N]

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RESULTS_DIR="$ROOT_DIR/build/benchmark-results"
LOG_DIR="$ROOT_DIR/build/benchmark"
BENCH_JS="$ROOT_DIR/scripts/bench.js"
AGENT_JAR="$ROOT_DIR/build/otel/opentelemetry-javaagent.jar"
APP_JAR="$ROOT_DIR/app/target/garage-1.0-SNAPSHOT.jar"
TELEMETRY_LOG="$ROOT_DIR/telemetry.log"

# run-agent.sh line 20 Base64 config: 5 static (domain, team, environment, region, service)
# and 10 dynamic rules on the com.example.Garage.park exit point — Car is passed as $arg0
# (brand, model, year, color, licensePlate, vin, mileage, fuelType, transmission,
# passengers[1].name). Set as OTEL_CUSTOM_AGENT_CONFIG for the enabled run; omitted for
# disabled. One enrich event per park call (vs the previous per-getter model's ten).
AGENT_CONFIG_B64="PGNvbmZpZ3VyYXRpb24+CiAgICA8c3RhdGljPgogICAgICAgIDxhdHRyaWJ1dGUga2V5PSJkb21haW4iIHZhbHVlPSJjYXJzIi8+CiAgICAgICAgPGF0dHJpYnV0ZSBrZXk9InRlYW0iIHZhbHVlPSJ3aW5uaW5nIi8+CiAgICAgICAgPGF0dHJpYnV0ZSBrZXk9ImVudmlyb25tZW50IiB2YWx1ZT0icHJvZHVjdGlvbiIvPgogICAgICAgIDxhdHRyaWJ1dGUga2V5PSJyZWdpb24iIHZhbHVlPSJldS13ZXN0Ii8+CiAgICAgICAgPGF0dHJpYnV0ZSBrZXk9InNlcnZpY2UiIHZhbHVlPSJnYXJhZ2UiLz4KICAgIDwvc3RhdGljPgogICAgPGR5bmFtaWM+CiAgICAgICAgPGVucmljaCBjbGFzcz0iY29tLmV4YW1wbGUuR2FyYWdlIiBtZXRob2Q9InBhcmsiPgogICAgICAgICAgICA8YXR0cmlidXRlIGtleT0iYnJhbmQiIHBhdGg9IiRhcmcwLmJyYW5kIi8+CiAgICAgICAgICAgIDxhdHRyaWJ1dGUga2V5PSJtb2RlbCIgcGF0aD0iJGFyZzAubW9kZWwiLz4KICAgICAgICAgICAgPGF0dHJpYnV0ZSBrZXk9InllYXIiIHBhdGg9IiRhcmcwLnllYXIiLz4KICAgICAgICAgICAgPGF0dHJpYnV0ZSBrZXk9ImNvbG9yIiBwYXRoPSIkYXJnMC5jb2xvciIvPgogICAgICAgICAgICA8YXR0cmlidXRlIGtleT0ibGljZW5zZVBsYXRlIiBwYXRoPSIkYXJnMC5saWNlbnNlUGxhdGUiLz4KICAgICAgICAgICAgPGF0dHJpYnV0ZSBrZXk9InZpbiIgcGF0aD0iJGFyZzAudmluIi8+CiAgICAgICAgICAgIDxhdHRyaWJ1dGUga2V5PSJtaWxlYWdlIiBwYXRoPSIkYXJnMC5taWxlYWdlIi8+CiAgICAgICAgICAgIDxhdHRyaWJ1dGUga2V5PSJmdWVsVHlwZSIgcGF0aD0iJGFyZzAuZnVlbFR5cGUiLz4KICAgICAgICAgICAgPGF0dHJpYnV0ZSBrZXk9InRyYW5zbWlzc2lvbiIgcGF0aD0iJGFyZzAudHJhbnNtaXNzaW9uIi8+CiAgICAgICAgICAgIDxhdHRyaWJ1dGUga2V5PSJwYXNzZW5nZXIxIiBwYXRoPSIkYXJnMC5wYXNzZW5nZXJzWzFdLm5hbWUiLz4KICAgICAgICA8L2VucmljaD4KICAgIDwvZHluYW1pYz4KPC9jb25maWd1cmF0aW9uPgo="

HOST=127.0.0.1
STUB_PORT=4318
APP_PORT=8081
STUB_URL="http://$HOST:$STUB_PORT/"
APP_URL="http://$HOST:$APP_PORT/cars"

# --- CLI ---
MODE=all
WARMUP=10000
MEASUREMENT=1000000
VUS=50

while [ $# -gt 0 ]; do
  case "$1" in
    --mode) MODE="$2"; shift 2;;
    --warmup) WARMUP="$2"; shift 2;;
    --measurement) MEASUREMENT="$2"; shift 2;;
    --vus) VUS="$2"; shift 2;;
    *) echo "[bench] unknown arg: $1" >&2; exit 1;;
  esac
done

TS="$(date -u +"%Y-%m-%dT%H-%M-%S")"
MARKDOWN="$RESULTS_DIR/runtime-state-benchmark-$TS.md"
mkdir -p "$RESULTS_DIR" "$LOG_DIR"

STUB_PID=""
APP_PID=""

# cleanup — reap any lingering subprocesses on exit.
cleanup() {
  if [ -n "$APP_PID" ]; then kill "$APP_PID" 2>/dev/null || true; wait "$APP_PID" 2>/dev/null || true; APP_PID=""; fi
  if [ -n "$STUB_PID" ]; then kill "$STUB_PID" 2>/dev/null || true; wait "$STUB_PID" 2>/dev/null || true; STUB_PID=""; fi
}
trap cleanup EXIT

# log — write a message to stderr prefixed with [bench], so stdout stays clean for k6 output.
log() { echo "[bench] $*" >&2; }

# compute_overhead <enabled_score> <disabled_score> — prints the percentage overhead as X.X.
compute_overhead() {
  awk -v e="$1" -v d="$2" 'BEGIN { if (d==0) { print 0 } else { printf "%.1f", (d-e)/d*100 } }'
}

# ==============================================================================
# Build
# ==============================================================================

# build_artifacts — compile the agent (./gradlew extendedAgent) + app (mvn package).
build_artifacts() {
  log "  ./gradlew extendedAgent..."
  (cd "$ROOT_DIR" && ./gradlew -q extendedAgent)
  log "  mvn app package..."
  (cd "$ROOT_DIR/app" && mvn -q clean package -DskipTests)
}

# ==============================================================================
# Process lifecycle
# ==============================================================================

# wait_http <url> <timeout_sec> <pid> — poll an HTTP endpoint until it responds or the
# process dies; returns 0 on readiness, 1 on timeout or process exit.
wait_http() {
  local url="$1"; local timeout="$2"; local pid="$3"
  local deadline=$(( $(date +%s) + timeout ))
  local code
  while [ "$(date +%s)" -lt "$deadline" ]; do
    if ! kill -0 "$pid" 2>/dev/null; then return 1; fi
    code="$(curl -s -o /dev/null -w "%{http_code}" --max-time 2 "$url" 2>/dev/null || printf "000")"
    if [[ "$code" =~ ^[1-4][0-9][0-9]$ ]]; then
      return 0
    fi
    sleep 0.2
  done
  return 1
}

# wait_port_free <port> — poll until nothing is listening on the port (or timeout after ~20s).
wait_port_free() {
  local port="$1"
  local i
  for i in $(seq 1 80); do
    if ! curl -s -o /dev/null --max-time 1 "http://$HOST:$port/" 2>/dev/null; then
      return 0
    fi
    sleep 0.25
  done
  return 1
}

# free_port <port> — kill listeners on <port>; SIGTERM, then SIGKILL after 1s. Idempotent.
free_port() {
  local port="$1"; local pids
  pids="$(lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null || true)"
  [ -z "$pids" ] && return 0
  log "  Port $port in use by pid $pids — killing..."
  kill $pids 2>/dev/null || true
  sleep 1
  pids="$(lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null || true)"
  [ -z "$pids" ] && return 0
  kill -9 $pids 2>/dev/null || true
  sleep 1
}

free_required_ports() {
  free_port "$STUB_PORT"
  free_port "$APP_PORT"
  free_port 14317
}

# start_stub — launch the OTLP stub as a background JVM, wait until ready.
start_stub() {
  log "Starting OTLP stub on $HOST:$STUB_PORT..."
  OTLP_STUB_HOST="$HOST" OTLP_STUB_PORT="$STUB_PORT" \
    java -cp "$APP_JAR" otel.OtlpStub >"$LOG_DIR/stub.log" 2>&1 &
  STUB_PID=$!
  if ! wait_http "$STUB_URL" 30 "$STUB_PID"; then
    log "  OTLP stub failed to start; see $LOG_DIR/stub.log"
    exit 1
  fi
  log "  OTLP stub ready (pid=$STUB_PID, log=$LOG_DIR/stub.log)."
}

# start_app <mode: enabled|disabled> <export_traces: true|false>
#
# The javaagent is always attached. The only difference between enabled/disabled is
# OTEL_CUSTOM_AGENT_CONFIG: set to the Base64 config for "enabled", empty for "disabled".
# An empty config makes RuntimeBridge publish RuntimeState.disabled() → classLoaderMatcher
# returns false → no ByteBuddy instrumentation → zero enrichment overhead.
#   local exp="otlp" always exports to collector
#   OTEL_EXPORTER_OTLP_ENDPOINT="http://172.19.0.7:4318" is the (non-stub) collector endpoint
start_app() {
  local mode="$1"; local export_traces="$2"
  local exp="none"
  [ "$export_traces" = "true" ] && exp="otlp"
  log "Starting app (mode=$mode, export=$exp)..."
  local env_cfg=""
  if [ "$mode" != "disabled" ]; then env_cfg="$AGENT_CONFIG_B64"; fi
  OTEL_CUSTOM_AGENT_CONFIG="$env_cfg" \
  OTEL_EXPORTER_OTLP_ENDPOINT="http://$HOST:$STUB_PORT" \
  OTEL_JAVAAGENT_DEBUG="false" \
  OTEL_METRICS_EXPORTER="none" \
  OTEL_LOGS_EXPORTER="none" \
  OTEL_TRACES_EXPORTER="$exp" \
  OTEL_SERVICE_NAME="car-app" \
    java -javaagent:"$AGENT_JAR" -jar "$APP_JAR" >"$LOG_DIR/app-$mode.log" 2>&1 &
  APP_PID=$!
  if ! wait_http "$APP_URL" 30 "$APP_PID"; then
    log "  App failed to start; see $LOG_DIR/app-$mode.log"
    exit 1
  fi
  log "  App ready on port $APP_PORT (pid=$APP_PID, log=$LOG_DIR/app-$mode.log)."
}

# stop_app — kill the app and wait until its port is free.
stop_app() {
  if [ -n "$APP_PID" ]; then
    kill "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
    APP_PID=""
  fi
  wait_port_free "$APP_PORT" || free_port "$APP_PORT"
}

# delete_garage — best-effort DELETE /cars to reset the in-memory garage between phases.
delete_garage() {
  curl -s -o /dev/null -X DELETE --max-time 10 "$APP_URL" 2>/dev/null || true
}

# ==============================================================================
# k6 execution
# ==============================================================================

# CURRENT_MODE tracks which mode ("enabled" / "disabled" / "verify") k6 is running for,
# so run_k6 can build per-run output filenames.
CURRENT_MODE=""

# run_k6 <phase: warmup|measurement> <iterations> <show_summary: yes|no>
#
# Invokes k6 with the bench.js script. Output:
#   - summary JSON (--summary-export) for jq parsing
#   - raw metrics JSON (--out json) for archival
#   - k6 stdout (official summary or nothing if --quiet) captured to a .log file
# Echoes the summary JSON path so the caller can parse it with jq_metric.
run_k6() {
  local phase="$1"; local iterations="$2"; local show="$3"
  local summary_json="$RESULTS_DIR/k6-${CURRENT_MODE}-${phase}-${TS}.summary.json"
  local raw="$RESULTS_DIR/k6-${CURRENT_MODE}-${phase}-${TS}.json"
  local outlog="$RESULTS_DIR/k6-${CURRENT_MODE}-${phase}-${TS}.log"
  log "  k6 $phase ($iterations iters, $VUS vus)..."
  local quiet_flag="--quiet"
  [ "$show" = "yes" ] && quiet_flag=""
  k6 run "$BENCH_JS" \
    --env BASE_URL="$APP_URL" \
    --env ITERATIONS="$iterations" \
    --env VUS="$VUS" \
    --summary-export="$summary_json" \
    --out json="$raw" \
    $quiet_flag >"$outlog" 2>&1 || { log "  k6 failed; see $outlog"; exit 1; }
  echo "$summary_json"
}

# jq_metric <file> <jq_path> — extract a metric from a k6 --summary-export JSON file.
# Usage: jq_metric summary.json '.metrics.http_reqs.rate'
jq_metric() {
  local file="$1"; local path="$2"
  jq -r "$path // 0" "$file" 2>/dev/null
}

# run_mode <mode: enabled|disabled>
#
# Starts the app in the given mode, fires the pre-warmup (not counted), then the
# measurement phase via k6. Echoes the throughput score (req/s) extracted from the
# k6 summary JSON. The app is stopped afterwards; the stub stays running across modes.
run_mode() {
  local mode="$1"
  CURRENT_MODE="$mode"
  log "=== Mode: $mode ==="
  start_app "$mode" false
  delete_garage
  log "  Pre-warmup: $WARMUP POST /cars (k6, not counted)..."
  run_k6 "warmup" "$WARMUP" "no" >/dev/null
  delete_garage
  log "  Measurement: $MEASUREMENT POST /cars..."
  local meas_summary_json
  meas_summary_json="$(run_k6 "measurement" "$MEASUREMENT" "yes")"
  local score
  score="$(jq_metric "$meas_summary_json" '.metrics.http_reqs.rate')"
  printf '%s\n' "$score"
  stop_app
}

# ==============================================================================
# Verify
# ==============================================================================

# verify_attributes — check telemetry.log for all configured attribute keys; print PASS or FAIL.
# Covers the 5 static keys + a representative dynamic key (brand) so the verify run confirms
# the 10-dynamic / 5-static configuration is actually being applied at runtime.
verify_attributes() {
  local ok=0
  local total=0
  for attr in domain team environment region service brand; do
    total=$((total + 1))
    if [ -f "$TELEMETRY_LOG" ] && grep -q "$attr" "$TELEMETRY_LOG"; then
      log "    + $attr present"
      ok=$((ok + 1))
    else
      log "    x $attr MISSING"
    fi
  done
  if [ "$ok" -eq "$total" ]; then
    printf 'enrichment_attributes: PASS\n'
  else
    printf 'enrichment_attributes: FAIL (%s/%s)\n' "$ok" "$total"
  fi
}

# run_verify — start the app with OTEL_TRACES_EXPORTER=otlp (enabled), fire 100 warmup +
# 1000 measurement POST /cars via k6, then check telemetry.log for enrichment attributes.
run_verify() {
  log "=== Verify run ==="
  if [ -f "$TELEMETRY_LOG" ]; then rm -f "$TELEMETRY_LOG"; fi
  CURRENT_MODE="verify"
  start_app "enabled" true
  delete_garage
  run_k6 "warmup" 100 "no" >/dev/null
  delete_garage
  run_k6 "measurement" 1000 "no" >/dev/null
  stop_app
  log "  Waiting 2s for OTLP export flush..."
  sleep 2
  verify_attributes
}

# ==============================================================================
# Markdown assembly
# ==============================================================================

# write_header — metadata block at the top of the result file.
write_header() {
  {
    printf '# runtime-state-benchmark\n'
    printf 'date: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'agent-commit: %s\n' "$(git -C "$ROOT_DIR" rev-parse --short HEAD 2>/dev/null || printf 'unknown')"
    printf 'k6-version: %s\n' "$(k6 version 2>/dev/null | head -1 || printf 'unknown')"
    printf 'java: %s\n' "$(java -version 2>&1 | head -1 | sed -E 's/^.*"([^"]+)".*/\1/')"
    printf 'config: warmup=%s measurement=%s vus=%s\n' "$WARMUP" "$MEASUREMENT" "$VUS"
    printf 'pre-warmup: %s POST /cars via k6 (not counted)\n\n' "$WARMUP"
  } >>"$MARKDOWN"
}

# write_row <mode> <phase> <summary_json> — append a markdown table row with throughput/p95/p99/failures.
write_row() {
  local mode="$1"; local phase="$2"; local file="$3"
  if [ ! -f "$file" ]; then return; fi
  local score p95 p99 fails
  score="$(jq_metric "$file" '.metrics.http_reqs.rate')"
  p95="$(jq_metric "$file" '.metrics.http_req_duration."p(95)"')"
  p99="$(jq_metric "$file" '.metrics.http_req_duration."p(99)"')"
  fails="$(jq_metric "$file" '.metrics.http_req_failed.rate')"
  printf '| %s | %s | %.0f | %.2f | %.2f | %s |\n' "$mode" "$phase" "${score:-0}" "${p95:-0}" "${p99:-0}" "${fails:-0}"
}

# ==============================================================================
# Main
# ==============================================================================

log "Building artifacts (agent + app)..."
build_artifacts

write_header

enabled_score=""
disabled_score=""

if [ "$MODE" = "all" ] || [ "$MODE" = "enabled" ] || [ "$MODE" = "disabled" ]; then
  free_required_ports
  start_stub
  if [ "$MODE" = "all" ] || [ "$MODE" = "enabled" ]; then
    enabled_score="$(run_mode "enabled")"
  fi
  if [ "$MODE" = "all" ] || [ "$MODE" = "disabled" ]; then
    disabled_score="$(run_mode "disabled")"
  fi
  cleanup
  trap - EXIT

  # Predicable output paths (same convention as run_k6 uses internally).
  enabled_summary_json="$RESULTS_DIR/k6-enabled-measurement-$TS.summary.json"
  disabled_summary_json="$RESULTS_DIR/k6-disabled-measurement-$TS.summary.json"
  enabled_k6_out="$RESULTS_DIR/k6-enabled-measurement-$TS.log"
  disabled_k6_out="$RESULTS_DIR/k6-disabled-measurement-$TS.log"

  # Throughput table (short summary) + overhead percentage.
  {
    printf '\n## Throughput\n\n'
    printf '| mode | phase | throughput (req/s) | p95 (ms) | p99 (ms) | failures |\n'
    printf '|------|-------|-------------------|----------|----------|----------|\n'
    write_row "enabled" "measurement" "$enabled_summary_json"
    write_row "disabled" "measurement" "$disabled_summary_json"
    printf '\n'
    if [ -n "$enabled_score" ] && [ -n "$disabled_score" ]; then
      overhead="$(compute_overhead "$enabled_score" "$disabled_score")"
      printf '**throughput overhead (enabled vs disabled): %s%%**\n\n' "$overhead"
    fi
  } >>"$MARKDOWN"

  # Official k6 summaries (full stdout from k6) for both modes + overhead.
  if [ -n "$enabled_score" ] && [ -n "$disabled_score" ]; then
    {
      printf '## k6 official summary — enabled (with instrumentation)\n\n'
      printf '```\n'
      cat "$enabled_k6_out" 2>/dev/null
      printf '```\n\n'
      printf '## k6 official summary — disabled (without instrumentation)\n\n'
      printf '```\n'
      cat "$disabled_k6_out" 2>/dev/null
      printf '```\n\n'
      overhead="$(compute_overhead "$enabled_score" "$disabled_score")"
      printf '## Overhead\n\n'
      printf '**throughput overhead (enabled vs disabled): %s%%**\n' "$overhead"
    } >>"$MARKDOWN"
  fi
fi

if [ "$MODE" = "all" ] || [ "$MODE" = "verify" ]; then
  free_required_ports
  start_stub
  verify_status="$(run_verify)"
  cleanup
  trap - EXIT
  {
    printf '## Verification\n\n'
    printf '%s\n' "$verify_status"
  } >>"$MARKDOWN"
fi

# --- Final stdout: the markdown file + both official k6 summaries + overhead ---

log "Results written to $MARKDOWN"
echo
cat "$MARKDOWN"

if [ -n "$enabled_score" ] && [ -n "$disabled_score" ]; then
  echo
  echo "================================================================================"
  echo "  k6 OFFICIAL SUMMARY — ENABLED (with instrumentation)"
  echo "================================================================================"
  cat "$enabled_k6_out" 2>/dev/null
  echo
  echo "================================================================================"
  echo "  k6 OFFICIAL SUMMARY — DISABLED (without instrumentation)"
  echo "================================================================================"
  cat "$disabled_k6_out" 2>/dev/null
  echo
  echo "================================================================================"
  overhead="$(compute_overhead "$enabled_score" "$disabled_score")"
  printf '  THROUGHPUT OVERHEAD (enabled vs disabled): %s%%\n' "$overhead"
  echo "================================================================================"
fi