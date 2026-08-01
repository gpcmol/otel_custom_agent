## 1. App Changes

- [x] 1.1 Edit `app/src/main/java/com/example/Main.java`: replace `CopyOnWriteArrayList<Car> garage` with `Collections.synchronizedList(new ArrayList<>())`
- [x] 1.2 Add `DELETE /cars` handler that clears the garage and responds with 204
- [x] 1.3 Add `static` helper `sendNoContent(exchange)` for 204 responses
- [x] 1.4 Edit `app/src/main/java/otel/OtlpStub.java`: configurable host/port via env vars (`OTLP_STUB_HOST`/`OTLP_STUB_PORT`); tolerate empty health-check bodies

## 2. Benchmark Orchestrator (Java)

- [x] 2.1 Create `scripts/AgentBenchmark.java`: `ProcessBuilder` for OTLP stub + app (enabled/disabled/verify), JDK 21 `java.net.http.HttpClient` (one instance, async), `--mode` / `--warmup` / `--measurement` / `--concurrency` args
- [x] 2.2 Implement app/stub process startup via ProcessBuilder with env vars (`OTEL_CUSTOM_AGENT_CONFIG`, `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_TRACES_EXPORTER`, etc. as env vars — NOT -D, since `RuntimeBridge` reads `System.getenv`); readiness poll on HTTP
- [x] 2.3 Implement load phase (warmup + measurement): async `HttpClient.sendAsync` with concurrency cap, fixed 1ms-bucket histogram for p95/p99, `DELETE /cars` between phases
- [x] 2.4 Implement random `Car` + `Passenger` JSON body generation (random brand + 2–3 random passenger names so `[1]` index is always valid)
- [x] 2.5 Redirect subprocess stdout/stderr to per-run log files (`build/benchmark/app-*.log`, `stub.log`) for clean console output
- [x] 2.6 Implement result assembly: write markdown to `build/benchmark-results/runtime-state-benchmark-<timestamp>.txt` with metadata header (commit, java, config, date) + throughput/p95/p99 table + overhead delta + verify status

## 3. Build Script

- [x] 3.1 Create `scripts/build.sh`: `./gradlew -q extendedAgent`, `cd app && mvn -q clean package -DskipTests`, compile `scripts/AgentBenchmark.java` to `build/benchmark/`

## 4. Verification

- [x] 4.1 `java -cp build/benchmark AgentBenchmark --mode all --warmup 10000 --measurement 1000000` runs end-to-end (1M each mode)
- [x] 4.2 Enabled + disabled throughput + p99 printed via `RESULT` lines
- [x] 4.3 Result file contains throughput table + overhead delta for both modes
- [x] 4.4 `enrichment_attributes: PASS` present in result file (brand/domain/team found in telemetry.log)
- [x] 4.5 `enrichment_attributes: PASS` confirmed via direct `telemetry.log` inspection (domain="cars", team="winning", brand=<random>, passengers[1].name=<random>)
- [x] 4.6 `./gradlew test` passes (app changes don't break existing agent tests)
