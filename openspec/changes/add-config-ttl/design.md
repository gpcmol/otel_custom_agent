## Context

Configuration flows: `OTEL_CUSTOM_AGENT_CONFIG` env var (Base64 XML) at startup, or
raw XML via the config webserver reload — both parsed by `ConfigurationParser`
into a `CompiledConfiguration`, then published via `RuntimeBridge.publish(...)` as
an immutable `RuntimeState`. The bridge keeps a weak reference per application
class loader and strong-pushes the state into the per-loader
`EnrichmentRuntime.STATE` via `setState`. The hot path (`EnrichmentRuntime.enrich`)
is a single volatile read of `STATE.enabled()` — a deliberate zero-extra-cost gate.

Constraints:

- The expiry mechanism must not add per-enrichment work (no clock read on the hot
  path) — the whole point is saving CPU cycles.
- State is per class loader; each loader has its own publish timeline.
- A disabled state already exists (`RuntimeState.disabled()` and the rollback path
  in `initialize()`). The disable mechanism is not new — only triggering it on a
  timer is.

## Goals / Non-Goals

**Goals:**

- Optional `ttl` attribute (ISO-8601 duration, e.g. `PT1H`, `PT30S`) declared in
  the XML config; after expiry the loader's runstate flips to disabled and no
  further enrichment happens. Absent attribute defaults to `PT24H`; an
  unparseable value logs one warning line and also defaults to `PT24H`.
- TTL travels with the config, re-armed on every publish (startup and webserver
  reload).
- Hot enrichment path unchanged: one volatile read, no clock, no timer check.
- The bridge emits exactly one log line when a scheduled TTL expiry disables a
  configuration. A later reload starts a new TTL and may therefore emit one new
  expiry log line.

**Non-Goals:**

- No re-enable API, no grace period — after expiry the config stays off (a fresh
  deploy or webserver reload re-enables with a new TTL).
- No env-var TTL (see D1).
- No per-exit-point time bookkeeping.
- No "no expiry" mode — every config expires; the 24h default is the longest
  window.

## Decisions

### D1: TTL is XML metadata, not an env var

`<configuration ttl="PT1H">` on the root element — ISO-8601 duration notation.
Rationale:

- The XML already is the complete config contract in both entry points
  (`OTEL_CUSTOM_AGENT_CONFIG` and the webserver). An env var would be a second,
  invisible half of the contract that must be kept in sync with the XML.
- Webserver reload: a reload replaces the config, and the TTL must re-arm from the
  new one. With XML metadata the parser extracts it from the same document and the
  reload path gets it for free. With an env var, the reload would have to consult
  a side channel whose value is now stale or mismatched.
- One document = one mental model: "this config lives for N more seconds".

Alternative considered: env var `OTEL_CUSTOM_AGENT_TTL` read alongside
`OTEL_CUSTOM_AGENT_CONFIG`. Rejected: split contract, stale on reload, and a second
knob to document. Table stakes the XML attribute covers; the env var adds nothing
the attribute doesn't.

### D2: Expiry is enforced bridge-side on a timer, not on the hot path
A single daemon `ScheduledExecutorService` in `RuntimeBridge` (created lazily on
first publish, shared by all loaders). Every publish schedules
`disable(loader)` at `System.nanoTime() + ttl` (CLOCK_MONOTONIC — immune to wall
clock jumps, correct for the "bounded debugging window" semantics); the TTL is
never absent after parsing (absent/unparseable → `PT24H`), so every publish
carries a deadline. A weak-keyed `EXPIRIES` map holds one `Expiry` object per
loader. Reload cancels and replaces that object. The task disables state only
when the object in the map is still the same object, which invalidates stale
tasks without a separate generation counter.

`disable(loader)` reuses the exact existing disabled-state pattern from
`initialize()`'s rollback:

- `STATES.put(loader, DISABLED_REF)` / `ENABLED.put(loader, FALSE)`
- strong-push `RuntimeState.disabled()` into the loader's `EnrichmentRuntime`
  via the existing `forwardStateToApplicationClassloader` — so the next
  `enrich()` call sees `enabled() == false` on its existing single volatile read.

No per-enrichment change. The old strong state in `EnrichmentRuntime.STATE` is
replaced by the disabled snapshot; in-flight enrichments that already read the old
state finish harmlessly (same semantics as a reload today).

Alternative considered: lazy expiry checked inside `enrich()` — rejected, it adds
a clock read to every enrichment, exactly the cost this change removes.

### D3: Parser converts ttl to Duration, default PT24H, log on failure

`ttl` is read from the root element in `ConfigurationParser.compile()` and
parsed with the standard library `java.time.Duration.parse` (the ISO-8601
`PnDTnHnMnS` format — `PT1H`, `PT30S`, `PT0.05S` for tests). Three cases:

- absent → default `Duration.ofHours(24)`;
- parseable → the parsed duration;
- unparseable (`DateTimeParseException`) → exactly one `WARNING` log line via
  the existing `System.getLogger` pattern, then the `PT24H` default.

A broken TTL must never fail the whole config: it is a safety ceiling, and
defaulting to the longest window is the safe direction. Whether the agent was
configured through Base64 startup or the webserver reload, the same rule
applies. `Duration` supports fractional seconds, so short test windows are
first-class (`PT0.05S`); enforcement precision in practice is bounded by the
scheduler, which is fine for a debug window of minutes to hours.

Negative durations are invalid and fall back to `PT24H` with one warning. Zero
is valid and schedules immediate disablement. If a parsed duration cannot be
represented in the scheduler's unit, it is invalid and follows the same
`PT24H` fallback with one warning.

Alternative considered: rejecting the config with a `ConfigurationException` on
an unparseable or absent TTL (fail-fast). Rejected: absent occurs for every
existing config, so fail-fast would disable every current deployment; and a
mis-typed attribute should degrade to the safest default, not kill the agent.

## Risks / Trade-offs

- [Scheduled disable fires while a reload is in flight] → the generation check
  plus disabling only touches the specific loader keyed by the captured generation;
  the reload either wins (new publish re-arms) or the disable wins (loader off,
  reload re-publishes after parse — both orders are consistent, final state is
  "disabled").
- [Existing configs without `ttl` now expire after 24h] → intended, explicit
  requirement; the agent is a debug tool and 24h is generous. Operators wanting
  a longer window set a larger `ttl`; the rollback knob is the XML, not a code
  change.
- [Daemon scheduler keeps a thread alive] → single lazily-created scheduled
  executor; it sleeps mostly, one thread per JVM, same footprint as the existing
  webserver thread. Fine for a debug-oriented agent.
- [After expiry the webserver still serves ACTIVE_XML] → intentional: the config
  document is still retrievable for inspection; only enrichment is off. `reload`
  with the same XML re-enables it with a fresh TTL — documented behaviour, not a
  bug.
- [Wall-clock drift vs monotonic] → TTL is measured with `System.nanoTime()`
  (monotonic); "N seconds of debugging" survives NTP adjustments, unlike
  `Instant`-based deadlines.
- [Loader retention by scheduled tasks] → scheduled work must not capture or
  otherwise strongly retain an application `ClassLoader`; the existing unload
  guarantee remains part of the design.

## Migration Plan

One behaviour change for existing deployments: configs without a `ttl` attribute
previously never expired, now they default to 24h. No deployment steps required —
the attribute is optional and defaults are built in. Rollback / extension knob is
purely in the XML: set an explicit `ttl` (e.g. `PT720H`) for long-running
instrumentation.

## Open Questions

- None blocking. Naming of the attribute (`ttl` vs `duration`) is cosmetic;
  `ttl` chosen for brevity, aligned with existing terse attribute names (`key`,
  `path`, `class`, `method`).
