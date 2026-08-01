## Context

The agent works in two modes:
- **Disabled** (`RuntimeState.disabled()`): `TraceAttributeInstrumentationModule.classLoaderMatcher()` returns `false` → ByteBuddy does not instrument the classes → no advice → zero enrichment overhead. Occurs when `OTEL_CUSTOM_AGENT_CONFIG` is not set (but the javaagent is still attached).
- **Enabled** (`RuntimeState.enabled()`): advice is injected on every method-exit of matched `Car` methods → `EnrichmentRuntime.enrich()` runs: `RuntimeBridge.state()` lookup (memoized volatile + synchronized fallback), span-context validation, static cache lookup, dynamic rule resolution via `RuleIndex` + `AccessorCache`, `AttributeValue`-writes to span.

The `app/` application (port 8081) has a `POST /cars` endpoint that deserializes a `Car` (record with `brand` + `List<Passenger>`), calls `car.getBrand()` and `car.getPassengers()` (these methods are dynamically configured in `run-agent.sh`), and adds the Car to an in-memory garage. The OTLP stub (configurable port) captures spans and writes them to `telemetry.log`.

`run-agent.sh` publishes a Base64-encoded config (line 20): static `domain=cars` + `team=winning`, dynamic `Car.brand` + `Car.passengers[1].name`.

## Goals / Non-Goals

**Goals:**
- Quantify the end-to-end throughput impact: 1M `POST /cars` with agent **enabled** vs **disabled** (both javaagent attached), measured in req/sec and p99 latency.
- Verify that enrichment attributes (`brand`, `domain`, `team`) correctly appear in OTLP spans via the stub (verify-run).
- Keep the benchmark reproducible: 10K warmup + 1M measurement, Java-native, a single `java -cp ... AgentBenchmark` invocation.

**Non-Goals:**
- Micro-benchmarking individual methods.
- Benchmarking OTLP export performance itself.
- Kubernetes/traefik deployment.

## Decisions

### All in Java, ProcessBuilder for process lifecycle

- **Decision**: A single `AgentBenchmark.java` starts the OTLP stub + agent-app as subprocesses via `java.lang.ProcessBuilder`, fires requests via JDK 21 `java.net.http.HttpClient` (async, connection-pooled), and writes markdown results. A small `build.sh` for compile/build only.
- **Rationale**: A single Java program is readable, debuggable, and has no shell-quoting/portability issues. `ProcessBuilder` gives exact control over JVM-args + env vars (javaagent, config, OTLP endpoint) per run. JDK HttpClient is stdlib.
- **Alternative considered**: Bash orchestrator. Rejected — shell is "not readable" and not debuggable; Java centralizes logic, error handling, and metrics in one language.

### HttpClient created once in constructor, requests reused

- **Decision**: `HttpClient` is created once (connection pool, async). The `HttpRequest` template (POST body) is rebuilt per request with a random `brand` + 1–3 random `Passenger` names, but the `HttpClient` (and its pooling) stays. Per the user's example: *"create the client that posts to /cars once in the constructor and then run the benchmarks."*
- **Rationale**: HttpClient instantiation is expensive; a single instance with pooling is the JDK-blessed approach. Random bodies match realistic variation; body-build is negligible compared to HTTP overhead.

### `OTEL_TRACES_EXPORTER=none` for throughput, stub for verify

- **Decision**: In throughput runs `OTEL_TRACES_EXPORTER=none` so OTLP export is not a bottleneck. Verify-run enabled with `otlp` export to the stub, checks `telemetry.log` for attributes.
- **Rationale**: The stub writes every span to `telemetry.log` (file I/O) — at 1M spans that dominates. With `none` you measure only instrumentation + enrich cost.

### Always-attach javaagent; toggle via `OTEL_CUSTOM_AGENT_CONFIG`

- **Decision**: Both enabled and disabled runs have `-javaagent:…` attached. Enabled sets `OTEL_CUSTOM_AGENT_CONFIG=<b64>`; disabled omits it → `RuntimeBridge.initialize` publishes `RuntimeState.disabled()` → `classLoaderMatcher` returns false → no instrumentation.
- **Rationale**: Isolation: the only variable is the RuntimeState. Without the javaagent in the disabled run you would also measure JVM-level differences (no OTel SDK init). With the javaagent in both runs the comparison is fair ("javaagent attached; with vs without active config").

### Fixed-bucket histogram for p95/p99

- **Decision**: Latency measured via a 1ms-bucket array histogram (0..60s), no boxing of 1M longs.
- **Rationale**: `ConcurrentLinkedQueue<Long>` of 1M longs = ~24MB overhead + sort O(n log n). A 60K-element `long[]` is 480KB, O(1) per sample, O(n) percentile.

### Result output: markdown with metadata + table

- **Decision**: Results written to `build/benchmark-results/runtime-state-benchmark.txt` with YAML frontmatter (commit hash, java version, timestamp, config), throughput/p95/p99 table per phase×mode, overhead delta, and verify status.
- **Rationale**: Machine-parseable (CI) + human-readable (markdown). One file per run, overwritable.

## Risks / Trade-offs

- **[Client saturation]**: The load generator itself is a JVM process; at concurrency 50+ GC or epoll can become the bottleneck. **Mitigation**: `p99` rises disproportionately at saturation; report both runs with equal params.
- **[JVM warmup variance]**: 10K warmup per phase; `ProcessBuilder` starts a fresh JVM per mode (fork). 2 runs for stability optional.
- **[Port reuse]**: App on 8081, stub on 4318; `destroy()` + `waitFor()` + port-free poll between runs prevents `BindException`.
- **[Span context validity]**: Enabled run export=none → `Span.current()` in `enrich` is a NoopSpan with valid context → `write()` path (no fallback). Verify-run activates export.

## Migration Plan

1. `app/src/main/java/com/example/Main.java` — COWAL → synchronized list; `DELETE /cars` + `sendNoContent` (done).
2. `app/src/main/java/otel/OtlpStub.java` — host/port via env vars; skip empty body (done).
3. Create `scripts/AgentBenchmark.java` — orchestrator + load-gen + result writer.
4. Create `scripts/build.sh` — build agent + app + compile benchmark.
5. `./scripts/build.sh && java -cp build/benchmark AgentBenchmark --mode all` → output.

## Open Questions

- (None — run-agent.sh config, OtlpStub, RuntimeState/advice-path, and COWAL bottleneck are all verified in the codebase.)