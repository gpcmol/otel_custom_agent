## Context

The agent has two modes:
- **Disabled** (`RuntimeState.disabled()`): `TraceAttributeInstrumentationModule.classLoaderMatcher()` returns `false` → no instrumentation; zero enrichment overhead.
- **Enabled** (`RuntimeState.enabled()`): ByteBuddy advice runs on matched `Car` methods; `EnrichmentRuntime.enrich()` enriches spans.

The `app/` (port 8081) exposes `POST /cars` (deserializes `Car`, calls `getBrand()` + `getPassengers()` — dynamically configured) and `DELETE /cars` (resets garage). The OTLP stub (`otel.OtlpStub`, configurable port) captures spans into `telemetry.log`. `run-agent.sh` line 20 holds the Base64 config: static `domain=cars` + `team=winning`, dynamic `Car.brand` + `Car.passengers[1].name`.

Prior state: a Java/JMH `benchmark/` submodule did the equivalent. This change replaces it with a bash + k6 combination. The load logic (10k warmup, 1M measurement, enabled/disabled/verify phases) is preserved.

## Goals / Non-Goals

**Goals:**
- Measure end-to-end throughput overhead of `POST /cars` with the agent enabled vs disabled via k6.
- Verify enrichment attributes (`brand`, `domain`, `team`) in `telemetry.log`.
- Write results (markdown + raw k6 metrics) to `build/benchmark-results/`.

**Non-Goals:**
- Micro-benchmarking ByteBuddy advice.
- Benchmarking OTLP export performance in throughput runs.
- Touching agent or app production code.

## Decisions

### Bash orchestrator + k6 load generator (no Java at the load-gen layer)

- **Decision**: A bash script (`scripts/bench.sh`) manages process lifecycle and assembles results; k6 (`scripts/bench.js`) generates the load.
- **Rationale**: k6 is purpose-built for HTTP load testing with built-in throughput/percentile metrics, checks, and threshold assertions. The shell orchestrates process startup which is already the cross-language boundary (javaagent + env vars). No extra JVM for the load generator.

### k6 `shared-iterations` executor for fixed iteration counts

- **Decision**: `executor: 'shared-iterations'` with `iterations: ITERATIONS, vus: VUS`. Two k6 invocations per mode (warmup, measurement), so warmup is excluded from the measurement metrics.
- **Rationale**: Honors user's "10k warmup, 1M measurement" exactly as iteration counts. k6 splits iterations across VUs at the configured concurrency.

### k6 default stdout summary captured; metrics parsed via `--summary-export` + `jq`

- **Decision**: k6 prints its built-in summary (Grafana banner + thresholds + HTTP/execution/network metrics) to stdout by default; `handleSummary` is NOT used. The orchestrator captures stdout per measurement run to a `.log` file and prints both summaries + the overhead percentage to stdout at the end. Metric extraction for the throughput table is done via `--summary-export` JSON + `jq`.
- **Rationale**: The user wants the official k6 summary as the final output. `handleSummary` suppresses or replaces k6's default stdout summary; removing it lets k6 print its native summary. `--summary-export` JSON is jq-parseable and doesn't interfere with stdout.

### `summaryTrendStats` extended to include `p(99)`

- **Decision**: `options.summaryTrendStats = ['avg','min','med','max','p(90)','p(95)','p(99)']` so `handleSummary` can read `p(99)` from `metrics.http_req_duration.values`.
- **Rationale**: k6's default trend stats omit `p(99)`; explicit config ensures it is present.

### `OTEL_TRACES_EXPORTER=none` for throughput, `otlp` for verify

- **Decision**: Throughput runs disable OTLP export (isolates the agent overhead); verify run enables it (so the stub receives spans and writes `telemetry.log` for attribute checking).
- **Rationale**: File-per-span I/O at 1M spans would dominate; `none` isolates instrumentation + enrich cost.

### Process lifecycle: background, env-var driven, curl readiness poll

- **Decision**: `java ... &` with env vars; readiness via `curl` loop on the HTTP endpoint; `trap cleanup EXIT` to reap any lingering children; `wait_port_free` via curl between runs.

## Risks / Trade-offs

- **[k6 binary dependency]**: requires `k6` on PATH. Mitigation: documented in `bench.sh`; macOS `brew install k6`.
- **[Saturation at high concurrency]**: at 50 vus, localhost throughput saturates around ~50k req/s; p99 reflects tail latency at saturation. Mitigation: `--vus` adjustable; report both modes with equal params.
- **[Background-process leak on early failure]**: `trap cleanup EXIT` kills stub/app on exit; a hard `kill -9` of bench.sh could orphan them. Mitigation: cleanup checks `kill -0` first.
- **[jq dependency]**: metric parsing requires `jq`. Mitigation: jq is pre-installed on macOS (`/usr/bin/jq`); documented in the script header.