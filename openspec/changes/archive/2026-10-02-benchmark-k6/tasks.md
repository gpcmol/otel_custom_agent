## 1. Remove the JMH benchmark

- [x] 1.1 Delete the `benchmark/` Maven submodule (pom.xml + all `src/main/java/org/otel/bench/*.java`)
- [x] 1.2 Edit `scripts/build.sh` to only build agent + app (drop the `cd benchmark && mvn package` step); update the printed run command to `scripts/bench.sh --mode all`
- [x] 1.3 Delete the `openspec/changes/benchmark-jmh/` change (superseded)

## 2. Markdown assembly

- [x] 2.1 Create `scripts/bench.js`: k6 load script
  - `shared-iterations` executor reading `__ENV.ITERATIONS` and `__ENV.VUS`
  - `summaryTrendStats: ['avg','min','med','max','p(90)','p(95)','p(99)']`
  - random car JSON body (random brand + 2–3 passenger names)
  - POST to `__ENV.BASE_URL` with `check(res, { 'status 201': r => r.status === 201 })`
  - thresholds: `http_req_failed < 0.01`, `checks > 0.99`
  - JSDoc on file, functions, and constants
- [x] 2.2 No `handleSummary` — k6 prints its built-in summary to stdout (captured by bash); metrics parsed via `--summary-export` JSON + `jq`

## 3. Bash orchestrator

- [x] 3.1 Create `scripts/bench.sh`: CLI parser (`--mode`, `--warmup`, `--measurement`, `--vus`, defaults `all/10000/1000000/50`); constants for host/ports/urls/agent-config-b64
- [x] 3.2 `build_artifacts()` runs `./gradlew -q extendedAgent` + `cd app && mvn -q clean package -DskipTests`
- [x] 3.3 `start_stub()` / `start_app(mode, exportTraces)` as background `java` with env vars; `wait_http` readiness via curl; logs to `build/benchmark/{stub,app-<mode>}.log`
- [x] 3.4 `stop_app()` + `wait_port_free()` between runs; `trap cleanup EXIT` reaps stray processes
- [x] 3.5 `run_k6(phase, iterations, show)` invokes `k6 run --env BASE_URL/ITERATIONS/VUS --summary-export=... --out json=...`; `show=yes` omits `--quiet` so k6 prints its official summary; `jq_metric()` parses the `--summary-export` JSON
- [x] 3.6 `run_mode(mode)` starts app, `DELETE /cars`, 10k warmup k6 (quiet), `DELETE /cars`, 1M measurement k6 (show summary), returns throughput score; stops app
- [x] 3.7 `run_verify()`: delete `telemetry.log`, start app(enabled, otlp), 100 warmup + 1000 measurement k6, stop app, sleep 2s, `verify_attributes()` checks `brand/domain/team` in log
- [x] 3.8 `compute_overhead()` helper (shared awk) for the overhead percentage; markdown header + throughput table + overhead + embedded official k6 summaries (code blocks) + verify status; `--measurement` default 1000000
- [x] 3.9 Final stdout: markdown + both official k6 summaries (ENABLED/DISABLED) + `compute_overhead` percentage

## 4. Verify

- [x] 4.1 Smoke run: `scripts/bench.sh --mode all --warmup 10 --measurement 100 --vus 10` writes markdown with a throughput table, overhead delta, and `enrichment_attributes: PASS`
- [x] 4.2 Full run: `scripts/bench.sh --mode all` (10k warmup + 1M measurement per mode) completes end-to-end; result file present with both modes + overhead delta + verify PASS
- [x] 4.3 k6 JSON + KV summary files exist per run in `build/benchmark-results/`
- [x] 4.4 `./gradlew test` passes (no agent/app changes)