## Why

The custom agent exists to instrument a JVM for a bounded debugging window. Every
enriched exit point costs CPU cycles (volatile state read, exit-point index lookup,
value resolution, span writes), so leaving instrumentation enabled indefinitely
wastes production resources. Today the only way to stop is a restart without
`OTEL_CUSTOM_AGENT_CONFIG` or a manual webserver reload. The configuration is already
time-boxed in intent — it should be able to expire itself, reverting to the
zero-cost disabled baseline automatically after a configured TTL.

## What Changes

- Add an optional `ttl` attribute in ISO-8601 duration notation (e.g. `PT1H`,
  `PT30S`) on the root `<configuration>` element of the XML contract. It travels
  with the config in `OTEL_CUSTOM_AGENT_CONFIG` and on webserver reload.
- After `ttl` seconds have elapsed since the configuration was published (startup
  or reload), the agent sets the affected application class loader's runstate to
  **disabled**: no further enrichment, no exit-point work, no span writes.
- The hot enrichment path stays untouched: expiry is enforced by a bridge-side
  scheduler that publishes the existing disabled `RuntimeState`, so `enrich()`
  keeps its single volatile-read gate.
- Reloading with a new config re-arms the TTL from the new config. A reload with no
  `ttl` uses the 24-hour default.
- Absent or unparseable `ttl` handling: absent = default of 24 hours (`PT24H`);
  unparseable or otherwise invalid value = one logged warning line and the same
  24-hour default. A
  broken TTL must never take the whole config down — it is a safety ceiling,
  and defaulting to the longest window is the safe direction. There is no
  "no expiry" mode anymore: every published config expires.
- When a TTL expires, the agent emits exactly one log line stating that
  instrumentation has been disabled.
- **Decision: TTL lives in the XML metadata, not as a separate env var.** The XML
  is already the single config contract (Base64 at startup, raw on reload); an env
  var would need side-channel plumbing, would be stale after a webserver reload,
  and would split the contract in two. Rationale is detailed in design.md.

## Capabilities

### New Capabilities

- `config-ttl`: time-to-live on the custom agent configuration — optional `ttl`
  (ISO-8601 duration, default `PT24H` when absent or unparseable) declared on
  the root `<configuration>` element; automatic runstate disable after expiry;
  re-arm on reload.

### Modified Capabilities

## Impact

- `ConfigurationParser` — read and convert the `ttl` attribute to a
  `java.time.Duration` (default `PT24H` + logged warning on parse failure).
- `CompiledConfiguration` / `RuntimeState` — carry the expiry deadline from parse
  to publish.
- `RuntimeBridge` — a `disable(loader)` path reusing the existing disabled-state
  mechanism, plus a single daemon scheduler that fires the disable.
- `EnrichmentRuntime` — unchanged hot path; only `setState` is reused to push the
  disabled state.
- Webserver reload path — re-arms the timer from the reloaded XML.
- Tests: parser validation, expiry behaviour, reload re-arm, backward
  compatibility.
