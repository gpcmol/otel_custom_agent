# config-ttl — Time-to-live on agent configuration

## ADDED Requirements

### Requirement: ttl attribute with ISO-8601 duration notation

The agent SHALL accept an optional `ttl` attribute on the root `<configuration>`
element of the XML contract. The value SHALL be an ISO-8601 duration string
(e.g. `PT1H`, `PT30S`, `PT0.05S`) parsed with `java.time.Duration`. When the
attribute is absent, the agent SHALL default the TTL to 24 hours (`PT24H`).
When the value cannot be parsed, is negative, or cannot be represented by the
expiry scheduler, the agent SHALL log one warning line and apply the same
24-hour default. A zero duration is valid and SHALL disable immediately. The
TTL SHALL be measured from the moment the config is published (startup or
reload). Every published config therefore expires.

#### Scenario: Startup config with explicit ttl

- **WHEN** `OTEL_CUSTOM_AGENT_CONFIG` contains a `<configuration ttl="PT1H">` config and it is published at T0
- **THEN** enrichment is active until T0 + 1 hour
- **AND** after T0 + 1 hour the runstate for that loader is disabled and no further enrichment occurs

#### Scenario: Startup config without ttl

- **WHEN** `OTEL_CUSTOM_AGENT_CONFIG` contains a config without a `ttl` attribute
- **THEN** the TTL defaults to 24 hours and enrichment is disabled after that period

#### Scenario: Unparseable ttl

- **WHEN** a config is activated with `<configuration ttl="abc">`
- **THEN** a warning log line is emitted stating the ttl could not be parsed and the default is used
- **AND** the 24-hour default TTL applies and the rest of the config stays active

### Requirement: Expiry is logged once

When a configuration expires, the agent SHALL emit exactly one log line stating
that instrumentation has been disabled. A later reload starts a new TTL and
may emit one new expiry log line.

### Requirement: Expiry disables enrichment

After TTL expiry the agent SHALL publish the existing disabled runstate for the
affected application class loader: the per-loader `RuntimeState.enabled` flag
SHALL become `false`, and subsequent exit-point invocations SHALL short-circuit
without enrichment, resolution, or span writes. The hot enrichment path SHALL
remain a single volatile read of the state gate — expiry enforcement SHALL NOT add
per-enrichment work such as clock reads.

#### Scenario: Enrichment stops after expiry

- **WHEN** a loader's config with `ttl="PT0.05S"` was published more than its TTL ago and an instrumented exit point fires
- **THEN** the exit point completes without any attribute enrichment or span writes

#### Scenario: In-flight enrichment finishes

- **WHEN** an enrichment is already executing on another thread at the moment the TTL fires
- **THEN** that in-flight enrichment completes without error and later calls are short-circuited

### Requirement: TTL re-arms on reload

A webserver reload SHALL re-arm the TTL from the newly submitted config: a reload
with a `ttl` attribute restarts the countdown from publish time with that value;
a reload without a `ttl` attribute re-arms the countdown with the 24-hour default.

#### Scenario: Reload with shorter ttl

- **WHEN** an active config with `ttl="PT1H"` is reloaded after 10 minutes with `ttl="PT1M"`
- **THEN** enrichment continues for 60 more seconds and then disables

#### Scenario: Reload without ttl re-arms default

- **WHEN** an active config with `ttl="PT1H"` is reloaded with a config that has no `ttl` attribute
- **THEN** the countdown restarts with the 24-hour default TTL

#### Scenario: Stale scheduled disable after reload

- **WHEN** a disable is scheduled for a loader and a reload publishes a new config before the schedule fires
- **THEN** the scheduled disable SHALL NOT affect the newly published state

### Requirement: TTL in the contract document

The `ttl` value SHALL travel inside the XML contract document itself (root
element attribute), not via a separate environment variable or side channel, so
both the startup Base64 path and the webserver reload path derive the TTL from
the same document they already parse.

#### Scenario: Both entry points honour the same attribute

- **WHEN** the same XML document with a `ttl` attribute is activated either at startup or via webserver reload
- **THEN** both paths apply the identical expiry semantics without additional configuration

### Requirement: Expiry timing is monotonic

The countdown SHALL be measured against a monotonic clock (e.g. `System.nanoTime()`),
so wall-clock adjustments (NTP, manual clock changes) do not shorten or extend
the intended debugging window.

### Requirement: Expiry preserves classloader unloading

The expiry mechanism SHALL NOT strongly retain an application `ClassLoader`
after that loader is otherwise unreachable. Scheduled expiry work and its
bookkeeping must preserve the existing classloader-unload guarantee.

#### Scenario: Clock jump does not affect TTL

- **WHEN** the system wall clock is adjusted while a `ttl="PT1H"` config is active
- **THEN** the disable still fires approximately 1 hour of real CPU time after publish
> Status: superseded. This capability is intentionally removed and is not a
> current agent requirement.
