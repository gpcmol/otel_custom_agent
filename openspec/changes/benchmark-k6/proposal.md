## Why

The previous Java/JMH benchmark (`benchmark/` submodule) measured agent overhead well, but adds a heavyweight Maven build, JMH annotation-processing, and a Java orchestrator on top of an already-Java project. The overhead logic itself (10k warmup + 1M measurement `POST /cars`, enabled vs disabled, verify enrichment attributes) is sound and should be kept; only the load generator should change. k6 (Grafana, Go) is a purpose-built load-testing tool with built-in throughput/percentile metrics, threshold assertions, and JSON output — a far better fit than hand-rolled load-generation in Java, with no extra JVM at the load-generator level.

## What Changes

- **Remove** `benchmark/` Maven submodule (JMH uber-jar, `AgentOverheadBenchmark.java`, orchestrator, all Java bench classes).
- **New `scripts/bench.sh`** — bash orchestrator that builds agent+app, starts the OTLP stub and the car-app (javaagent enabled/disabled) as background processes, fires a 10k k6 pre-warmup + 1M k6 measurement phase per mode, computes throughput overhead, runs a verify phase, and writes a markdown result file.
- **New `scripts/bench.js`** — k6 load script (`shared-iterations` executor) that posts random `POST /cars` JSON bodies and asserts `201`; k6's built-in summary is printed to stdout and captured by the bash orchestrator. Metric extraction is via `--summary-export` JSON + `jq` (no `handleSummary` needed).
- **`scripts/build.sh`** — simplified to only build agent + app (no benchmark step); the benchmark runs via `scripts/bench.sh` which reuses these artifacts.
- **Results** — markdown at `build/benchmark-results/runtime-state-benchmark-<timestamp>.md` with the official k6 summaries embedded; plus per-run k6 raw JSON (`--out json`) and summary-export JSON (`--summary-export` + `jq`) in the same directory.
- **No production code change** — agent (`src/main`) and `app/` stay untouched.

## Capabilities

### New Capabilities
- `benchmark-k6`: A bash-orchestrated, k6-driven end-to-end overhead benchmark that runs the stub + agent-app per mode, fires 10k warmup + 1M measurement `POST /cars` via k6, reports throughput/p95/p99 per mode plus the overhead delta, and verifies enrichment attributes in `telemetry.log`.

### Modified Capabilities
(none)

## Impact

- **Code**: delete `benchmark/` Java submodule; add `scripts/bench.sh`, `scripts/bench.js`.
- **Build**: `scripts/build.sh` simplified (drops the `cd benchmark && mvn package` step).
- **Runtime**: bash spawns the stub/app as background JVMs; k6 runs as a separate Go process; JDK 21 for the JVMs, k6 v2.x for load.
- **Dependencies**: removes JMH 1.37 from the repo; adds a runtime dependency on `k6` (already installed on the dev machine; CI would need a k6 binary). No changes to `app/pom.xml` or root `build.gradle.kts`.