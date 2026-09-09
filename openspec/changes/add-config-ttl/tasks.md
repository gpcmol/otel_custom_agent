## 1. Parser: ttl attribute

- [x] 1.1 Add `ttl` (`java.time.Duration`, never null) field to `CompiledConfiguration`
- [x] 1.2 In `ConfigurationParser.compile()`, read optional `ttl` attribute from root element: absent → `Duration.ofHours(24)`; parseable non-negative value → `Duration.parse(value)`; negative, unparseable, or scheduler-unrepresentable values → one `WARNING` log line and `Duration.ofHours(24)`. Zero is valid and expires immediately. A bad ttl never throws
- [x] 1.3 Ensure reload (`parseXml`) and startup (`parse`) paths both carry the parsed `ttl` through `CompiledConfiguration`

## 2. Bridge: scheduler and disable path

- [x] 2.1 Add a lazily-created single-thread daemon `ScheduledExecutorService` in `RuntimeBridge` (created on first publish), without strongly retaining application classloaders through scheduled tasks
- [x] 2.2 Track one per-loader `Expiry` object; scheduled tasks are valid only while that object remains current in the expiry map
- [x] 2.3 Add `disable(loader)`: `STATES.put(loader, DISABLED_REF)` + `ENABLED.put(loader, FALSE)` and strong-push `RuntimeState.disabled()` via existing `forwardStateToApplicationClassloader`
- [x] 2.4 In `publishLocked`, schedule `disable(loader)` at `now + ttl` (monotonic via `System.nanoTime()`) for every publish — ttl is always non-null after parsing (default `PT24H`)
- [x] 2.5 Stale-disable guard: the scheduled task no-ops when its `Expiry` object is no longer current
- [x] 2.6 Extend `resetForTesting()` to cancel all scheduled tasks (no leaked timers between tests)
- [x] 2.7 Emit exactly one log line when each TTL expiry disables instrumentation

## 3. Behaviour verification

- [x] 3.1 `ConfigurationParserTest`: `ttl="PT1H"` → 1 hour; absent ttl → `Duration.ofHours(24)`; `ttl="abc"` → no exception, `Duration.ofHours(24)`
- [x] 3.2 New `ConfigTtlTest` (bridge-level): publish with `ttl="PT0.05S"`, assert `RuntimeBridge.state(loader).enabled()` flips false after expiry without any reload
- [x] 3.3 Same test class: reload with `ttl="PT0.05S"` re-arms (state stays enabled, disables again after the new ttl); reload without ttl → state stays enabled, disables after the 24h default (assert via exposed ttl/remaining-time, do not wait 24h)
- [x] 3.4 Test `disable()` strong-push: after TTL fires, `EnrichmentRuntime.state()` for the loader reflects the disabled snapshot
- [x] 3.6 Test invalid negative and scheduler-overflow TTL values fall back with one warning
- [ ] 3.7 Test expiry logging occurs once per expiry and reloads can produce a new expiry log
- [x] 3.8 Test scheduled expiry bookkeeping does not retain an otherwise unreachable application classloader
- [x] 3.5 Run full test suite (repo's test command) and confirm no existing test regressions

## 4. Docs

- [x] 4.1 Update the XML contract documentation (README / draft spec) with the optional `ttl` attribute, ISO-8601 duration notation, and the 24-hour default with logged fallback
> Status: superseded. These completed TTL tasks describe removed functionality.
