## ADDED Requirements

### Requirement: Benchmark is k6-driven with adjustable iteration counts
The benchmark SHALL be driven by a k6 load script (`scripts/bench.js`) using the `shared-iterations` executor, with iteration counts and VUs adjustable via CLI flags (`--warmup`, `--measurement`, `--vus`) on the bash orchestrator (`scripts/bench.sh`).

#### Scenario: Iterations adjustable from CLI
- **WHEN** the benchmark is run with `--warmup 1000 --measurement 10000 --vus 20`
- **THEN** k6 SHALL fire 1,000 warmup + 10,000 measurement `POST /cars` iterations at 20 concurrent virtual users, with no source changes required

### Requirement: Orchestrator is a bash script managing stub and app subprocesses
The benchmark SHALL be orchestrated by `scripts/bench.sh`, which builds agent + app, starts the OTLP stub and the car-app as background processes via env-var-driven `java` commands, runs k6 per phase, tears down subprocesses, and assembles the result markdown.

#### Scenario: Enabled run sets car config
- **WHEN** the orchestrator starts the app for the enabled run
- **THEN** `OTEL_CUSTOM_AGENT_CONFIG` SHALL be set to the Base64 car config from run-agent.sh, the javaagent SHALL be attached (`-javaagent`), and `OTEL_TRACES_EXPORTER=none` SHALL be set

#### Scenario: Disabled run omits config but keeps javaagent
- **WHEN** the orchestrator starts the app for the disabled run
- **THEN** `OTEL_CUSTOM_AGENT_CONFIG` SHALL be empty while `-javaagent` remains attached and `OTEL_TRACES_EXPORTER=none` SHALL be set

#### Scenario: Isolation — javaagent always attached
- **WHEN** both enabled and disabled runs have completed
- **THEN** the javaagent SHALL have been attached in both runs (enabled and disabled); the ONLY variable between runs SHALL be whether `OTEL_CUSTOM_AGENT_CONFIG` is set or empty

#### Scenario: Readiness polled before k6 run
- **WHEN** the orchestrator starts the stub or app
- **THEN** it SHALL poll the HTTP endpoint via curl until ready before proceeding to invoke k6

### Requirement: k6 fires POST /cars with random car bodies and asserts 201
The k6 script SHALL send a `POST /cars` request with a JSON body containing a random brand and 2–3 passenger names to `http://127.0.0.1:8081/cars`, and SHALL assert the response is `201` via a k6 check.

#### Scenario: Benchmark sends POST /cars
- **WHEN** k6 invokes the default function
- **THEN** a `POST /cars` request with a random car body SHALL be sent and the response status SHALL be asserted equal to `201`

#### Scenario: Non-201 responses counted as failures
- **WHEN** the app responds with a status code other than `201`
- **THEN** the k6 check SHALL fail and the failures rate SHALL rise above the threshold, failing the run

### Requirement: App pre-warmup fires 10,000 POST /cars before the measured phase
Before the measurement phase per mode, the orchestrator SHALL fire **10,000** `POST /cars` iterations via k6 (warmup) with a `DELETE /cars` reset beforehand; the warmup metrics SHALL NOT be used as the throughput number.

#### Scenario: 10k pre-warmup posts fired
- **WHEN** the orchestrator starts a mode and before it runs the measurement phase
- **THEN** 10,000 `POST /cars` iterations SHALL be sent via k6 with `DELETE /cars` issued beforehand

### Requirement: Measurement phase fires 1,000,000 POST /cars per mode
The measurement phase SHALL fire **1,000,000** `POST /cars` iterations per mode by default (adjustable via `--measurement`).

#### Scenario: Default measurement is 1M posts
- **WHEN** `bench.sh --mode all` is invoked without `--measurement`
- **THEN** k6 SHALL fire 1,000,000 measurement `POST /cars` iterations for both enabled and disabled modes

### Requirement: Official k6 summary printed to stdout for both modes
After all throughput runs complete, the orchestrator SHALL print to stdout the full official k6 summary (k6's built-in stdout output, including the Grafana banner, thresholds, total results, HTTP metrics, execution metrics, and network metrics) for each mode — enabled first, then disabled.

#### Scenario: Enabled k6 summary printed
- **WHEN** the enabled measurement run completes
- **THEN** the official k6 stdout summary SHALL be captured to a log file and printed to stdout after the markdown

#### Scenario: Disabled k6 summary printed
- **WHEN** the disabled measurement run completes
- **THEN** the official k6 stdout summary SHALL be captured to a log file and printed to stdout after the enabled summary

#### Scenario: Overhead percentage printed last
- **WHEN** both official k6 summaries have been printed
- **THEN** the orchestrator SHALL print a final `THROUGHPUT OVERHEAD (enabled vs disabled): X.X%` line

### Requirement: Throughput, p95, p99 and failures are reported per mode
The bash orchestrator SHALL parse k6's `--summary-export` JSON output (via jq) and report throughput (req/s), p95 (ms), p99 (ms), and failures rate per mode in a markdown table.

#### Scenario: Throughput reported per mode
- **WHEN** the measurement phase completes for both modes
- **THEN** the markdown SHALL contain a per-mode row with throughput, p95, p99, and failures

### Requirement: Throughput overhead delta is reported
The benchmark SHALL compute the throughput overhead percentage `((disabled - enabled) / disabled * 100)` via a shared helper and append it to both the markdown and the final stdout output.

#### Scenario: Overhead delta computed
- **WHEN** both enabled and disabled measurement scores are available
- **THEN** the markdown and stdout SHALL contain a `throughput overhead (enabled vs disabled): X.X%` line

### Requirement: Official k6 summaries embedded in the markdown result file
The benchmark SHALL embed both official k6 stdout summaries (enabled and disabled) in fenced code blocks within the markdown result file, followed by the overhead percentage as a final `## Overhead` section.

#### Scenario: k6 summaries embedded in markdown
- **WHEN** `bench.sh` completes a throughput run
- **THEN** the markdown SHALL contain a `## k6 official summary — enabled (with instrumentation)` section with the captured k6 stdout in a code block, a `## k6 official summary — disabled (without instrumentation)` section with the same, and a `## Overhead` section with the percentage

### Requirement: Results written as markdown plus raw k6 metrics
The benchmark SHALL write a markdown result file to `build/benchmark-results/runtime-state-benchmark-<timestamp>.md` with a metadata header (date, agent commit, k6 version, java version, config) and the throughput/overhead/verify tables. Each k6 run SHALL also produce a raw JSON metrics file (`--out json`) and a `--summary-export` JSON file (for jq parsing) in the same directory.

#### Scenario: Markdown file created
- **WHEN** `bench.sh` completes
- **THEN** a markdown file SHALL exist with date, commit, k6 version, java version, the throughput table, the official k6 summaries, the overhead delta, and the verify status

#### Scenario: k6 JSON and summary-export written per run
- **WHEN** each k6 invocation completes
- **THEN** a raw k6 JSON metrics file SHALL exist at `build/benchmark-results/k6-<mode>-<phase>-<timestamp>.json` and a `--summary-export` JSON at `build/benchmark-results/k6-<mode>-<phase>-<timestamp>.summary.json`

### Requirement: Verify run checks enrichment attributes via k6 + direct file inspection
The benchmark SHALL perform a verify run that starts the app with `OTEL_TRACES_EXPORTER=otlp` (enabled), fires 100 warmup + 1000 measurement `POST /cars` via k6, then inspects `telemetry.log` for the static (`brand`, `domain`, `team`) enrichment attributes and reports `enrichment_attributes: PASS|FAIL`.

#### Scenario: Static and dynamic attributes present
- **WHEN** the verify run inspects `telemetry.log`
- **THEN** `brand`, `domain`, and `team` SHALL each appear and the report SHALL be `enrichment_attributes: PASS`

### Requirement: Java/JMH benchmark submodule is removed
The previous `benchmark/` Maven submodule (JMH-driven) SHALL be deleted, and `scripts/build.sh` SHALL no longer compile any benchmark Java code.

#### Scenario: benchmark/ removed
- **WHEN** the change is applied
- **THEN** the `benchmark/` directory SHALL not exist and `scripts/build.sh` SHALL build only the agent and the app

### Requirement: No agent or app production code change
Neither the agent (`src/main`) nor the app (`app/`) production code SHALL be modified by this change.

#### Scenario: Root agent build untouched
- **WHEN** the change is applied
- **THEN** `build.gradle.kts` and `app/pom.xml` SHALL remain unchanged