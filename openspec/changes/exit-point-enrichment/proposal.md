## Why

The current instrumentation enriches on **every instance-method exit** of configured root classes. In practice this is both semantically wrong (attributes land on whatever span happens to be active when a getter is called, not the span producing the work) and a performance bottleneck: one logical request triggers N enrich calls — each re-resolving and re-writing the same attributes to the same span. A 20% throughput overhead is dominated by redundant `Span.setAttribute` calls, not by accessor invocation. The agent needs to enrich at the **span-producing boundary** (the method that closes the unit of work), not at every getter.

This change replaces per-method instrumentation with **exit-point** instrumentation: the team declares the method that closes the work unit (`<enrich class method>`), and the agent enriches once, at that point, reading properties from a configurable root (`$this`, `$argN`, or `$return`). This aligns with how OpenTelemetry itself instruments servlets/controllers/services and eliminates redundant enrich calls.

## What Changes

- **BREAKING**: Replace per-instance-method instrumentation with single exit-point instrumentation. The `<dynamic><attribute path="com.example.Car.brand"/></dynamic>` form is replaced by `<enrich class method>` blocks with path roots (`$this`/`$argN`/`$return`).
- **BREAKING**: Configuration XML schema changes. Old configs must be rewritten (see migration note below).
- Add `RootSource` model: `THIS` | `ARG(n)` | `RETURN` — the advice-context anchor a path resolves from.
- Add `<enrich class method>` configuration block bundling all attribute rules that apply at one exit point.
- Instrumentation matches the **declared class** via `hasSuperType`; advice is installed on all non-static instance methods of matching types (ByteBuddy installs matchers at bootstrap, before config is loaded) and filtered at runtime via `ExitPointIndex.find`. Advice is split into `VoidAdvice` (void methods, no `@Advice.Return`) and `ValueAdvice` (non-void, with `@Advice.Return`), both receiving `@Advice.This`, `@Advice.AllArguments`, and `@Advice.Origin Method`.
- `ValueResolver` resolves from the configured `RootSource` instead of always from the receiver. The resolver itself is structurally unchanged — only the root object passed in differs.
- `RuleIndex` is replaced by an `ExitPointIndex` keyed by `(Class, methodName)` instead of by root class alone.
- `EnrichmentRuntime.enrich(receiver)` becomes `enrich(state, thisOrNull, args, returnOrNull)` selecting the exit point from the receiver's class + invoked method.
- `AccessorCache`, `AttributeConverter`, `SpanWriter`, `FifoCache`, `RuntimeBridge`, `ValueResolver` (path-segment logic) — unchanged.
- Benchmark app `Main.java`: remove the 10 manual getter calls; add a `Garage.park(Car)` domain method that the config instruments as the exit point.

### Migration note
Old config:
```xml
<dynamic><attribute key="brand" path="com.example.Car.brand"/></dynamic>
```
New config:
```xml
<dynamic>
  <enrich class="com.example.Garage" method="park">
    <attribute key="brand" path="$arg0.brand"/>
  </enrich>
</dynamic>
```
The team manually rewrites configs; no automatic migration is provided (YAGNI — few configs exist).

## Capabilities

### New Capabilities

- `exit-point-enrichment`: Configured static and runtime-derived OpenTelemetry span attribute enrichment, applied at a declared exit-point method with configurable root source (`$this`/`$argN`/`$return`).

### Modified Capabilities

- `trace-attribute-enrichment`: **BREAKING** — replaces the existing per-instance-method instrumentation model with exit-point instrumentation; the XML schema, `RuleIndex`/`EnrichmentRuntime`/`TraceAttributeTypeInstrumentation` semantics change. The old `trace-attribute-enrichment` spec is superseded; the new `exit-point-enrichment` spec takes its place.

## Impact

- **Configuration**: new `<enrich class method>` XML schema with `$this`/`$argN`/`$return` path roots. Old configs are incompatible.
- **Model**: `DynamicAttributeRule` gains a `RootSource`; new `ExitPoint` record groups rules by `(Class, methodName)`. `RuleIndex` → `ExitPointIndex`.
- **Parser**: `ConfigurationParser` parses `<enrich>` blocks and the `$`-prefix path roots.
- **Instrumentation**: `TraceAttributeTypeInstrumentation` matches types whose supertype is a configured exit-point class (not all instance methods of root types); advice is split into `VoidAdvice`/`ValueAdvice` by return type; `ExitPointIndex.find` filters non-exit-point calls at runtime. `RuntimeBridge.initialize` pre-publishes `ROOT_NAMES` + `ENABLED=true` before the parser runs so ByteBuddy's nested `Class.forName` triggers the type matcher.
- **Runtime**: `EnrichmentRuntime.enrich` signature changes to accept receiver, args, return; selects `ExitPoint` by `(receiverClass, invokedMethod)`.
- **Resolver**: `ValueResolver` resolves from an arbitrary root object instead of always the receiver — structural change only, the segment-walking logic is unchanged.
- **Accessor/Cache/Telemetry**: no changes.
- **Benchmark**: the 10 manual getter calls in `Main.CarsHandler.handle` are removed; a `Garage.park(Car)` domain method becomes the configured exit point. Expected overhead reduction from ~20% to <5%.
- **Tests**: `ValueResolverTest`, `ConfigurationParserTest`, integration tests updated to the new config schema; new tests cover `$this`/`$arg0`/`$return` root sources and exit-point selection.