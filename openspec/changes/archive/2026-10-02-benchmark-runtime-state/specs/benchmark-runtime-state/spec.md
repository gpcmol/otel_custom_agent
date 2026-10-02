## ADDED Requirements

### Requirement: Delete-all-cars endpoint resets garage between benchmark phases
The app SHALL provide a `DELETE /cars` endpoint that clears the in-memory garage list.

#### Scenario: DELETE clears garage
- **WHEN** `DELETE /cars` is sent to the app
- **THEN** the garage list SHALL be empty and the server SHALL respond with `204 No Content`

#### Scenario: Garage survives GET after DELETE
- **WHEN** a GET `/cars` is sent after `DELETE /cars`
- **THEN** the server SHALL respond with `200` and an empty JSON array `[]`

### Requirement: Garage list performs writes efficiently under 1M additions
The garage list SHALL use a thread-safe list implementation that does not copy the backing array on every write.

#### Scenario: High-write throughput
- **WHEN** 1M cars are POSTed to `/cars` in a single measurement phase
- **THEN** the app SHALL not exhibit `CopyOnWriteArrayList`-style write amplification

### Requirement: Benchmark client fires 1M requests with warmup via Java HttpClient
The benchmark SHALL use JDK 21 `java.net.http.HttpClient` to execute a warmup phase of 10,000 `POST /cars` requests followed by a measurement phase of 1,000,000 `POST /cars` requests, async with configurable concurrency.

#### Scenario: Warmup phase completes
- **WHEN** the benchmark runs in throughput mode
- **THEN** 10,000 warmup requests SHALL complete before measurement begins

#### Scenario: Measurement phase fires 1M requests
- **WHEN** measurement begins
- **THEN** 1,000,000 `POST /cars` requests SHALL be sent with random car + passenger data

### Requirement: Benchmark orchestrator runs agent enabled vs disabled via ProcessBuilder
The benchmark SHALL start the app twice via `java.lang.ProcessBuilder`: once with `-javaagent` + `OTEL_CUSTOM_AGENT_CONFIG_FILE` (enabled), once with `-javaagent` but no file config (disabled → `RuntimeState.disabled()`).

#### Scenario: Disabled run has no config
- **WHEN** the benchmark starts the app for the disabled run
- **THEN** `OTEL_CUSTOM_AGENT_CONFIG_FILE` SHALL be unset while `-javaagent` remains attached

#### Scenario: Enabled run has car config
- **WHEN** the benchmark starts the app for the enabled run
- **THEN** `OTEL_CUSTOM_AGENT_CONFIG_FILE` SHALL be set to the XML car config from run-agent.sh

### Requirement: Throughput and latency metrics are reported
The benchmark SHALL report throughput (requests per second) and p95/p99 latency for each phase.

#### Scenario: Throughput reported
- **WHEN** the measurement phase completes
- **THEN** the benchmark SHALL print throughput for the 1M-request window

#### Scenario: p99 latency reported
- **WHEN** the measurement phase completes
- **THEN** the benchmark SHALL print p99 for the 1M-request window

### Requirement: Results written to structured markdown file
The benchmark SHALL write results to `build/benchmark-results/runtime-state-benchmark.txt` with metadata header and a throughput/latency table for both modes.

#### Scenario: Markdown file created
- **WHEN** the benchmark completes a throughput run
- **THEN** `build/benchmark-results/runtime-state-benchmark.txt` SHALL exist with commit hash, java version, timestamp, and a phase|mode|throughput|p95|p99|errors table

### Requirement: OTLP stub receives and logs spans
The OTLP stub SHALL accept HTTP OTLP trace export requests and append parsed span data to `telemetry.log`.

#### Scenario: Spans received after enabled run
- **WHEN** the enabled benchmark run exports spans with `OTEL_TRACES_EXPORTER=otlp`
- **THEN** `telemetry.log` SHALL contain `ExportTraceServiceRequest` entries

### Requirement: Enrichment attributes present on exported spans
In the verify run, exported spans SHALL carry the configured static and dynamic enrichment attributes.

#### Scenario: Static attributes present
- **WHEN** a `POST /cars` is processed under the enabled agent
- **THEN** exported spans SHALL have attributes `domain` = `cars` and `team` = `winning`

#### Scenario: Dynamic attributes present
- **WHEN** a `POST /cars` is processed under the enabled agent
- **THEN** exported spans SHALL have attribute `brand` equal to the Car's brand and `passengers[1].name` equal to the second Passenger's name
