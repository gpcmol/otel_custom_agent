## Why

The OTel custom agent instruments application classes via ByteBuddy advice that calls `EnrichmentRuntime.enrich()` on every method-exit. This overhead is not yet quantified. An end-to-end benchmark with the real `app/` application — 1M `POST /cars` requests with the agent enabled vs disabled — measures the real throughput impact of instrumentation + enrichment + span-weak, exactly as it would happen in production. JMH in isolation does not capture the ByteBuddy advice cost.

## What Changes

- **App**: `Main.java` — replace `CopyOnWriteArrayList` with `Collections.synchronizedList(new ArrayList<>())` (COWAL is O(n) per write, a bottleneck at 1M adds); add a `DELETE /cars` endpoint. `OtlpStub.java` — make host/port configurable via env vars; ignore empty health-check bodies; bind to localhost to avoid container port conflicts.
- **Benchmark (Java)**: `scripts/AgentBenchmark.java` — a single self-contained Java benchmark that starts the OTLP stub + agent-app (via `ProcessBuilder`), fires 10K warmup + 1M measurement `POST /cars` requests via JDK 21 `java.net.http.HttpClient`, measures throughput + p95/p99, and writes structured results. It orchestrates enabled (javaagent + config) vs disabled (javaagent, no config → `RuntimeState.disabled()`) runs + a verify-run with the OTLP stub.
- **Build script (small)**: `scripts/build.sh` — `./gradlew extendedAgent` + `cd app && mvn package -DskipTests` + compile `AgentBenchmark.java`. Build only, no benchmark logic.
- **No production code change**: only `app/` and `scripts/` changes. The agent itself (src/main) stays untouched; the benchmark measures the agent as built.

## Capabilities

### New Capabilities

- `benchmark-runtime-state`: Java-native end-to-end benchmark, 1M requests, agent enabled vs disabled, with OTLP-stub verification of enrichment attributes

### Modified Capabilities

(none)

## Impact

- **Build**: small `scripts/build.sh`; JMH not needed (JDK 25 HttpClient stdlib). `app/pom.xml` unchanged.
- **App**: `app/src/main/java/com/example/Main.java` (COWAL → synchronized list; `DELETE /cars`); `app/src/main/java/otel/OtlpStub.java` (configurable host/port).
- **Scripts**: `scripts/AgentBenchmark.java` (orchestrator + load-gen), `scripts/build.sh` (build-only). Replaces the old `benchmark-overhead.sh` + `LoadGenerator.java`.
- **Runtime**: benchmark starts the agent via the `run-agent.sh` pattern (javaagent + `OTEL_CUSTOM_AGENT_CONFIG_FILE`).
