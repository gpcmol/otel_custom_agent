## Context

The agent enriches OpenTelemetry spans with attributes sourced from application objects. Today, instrumentation fires `EnrichmentRuntime.enrich(receiver)` on **every non-static instance-method exit** of every configured root class (`TraceAttributeTypeInstrumentation.transform`). In the benchmark app, the handler explicitly calls 10 `Car` getters per POST, so enrichment runs 10× per request — each call re-resolving and re-writing the same 15 attributes to the same span (150 `Span.setAttribute` calls total). The 20% throughput overhead is dominated by `Span.setAttribute` work, not by accessor invocation — which is why the `LambdaMetafactory` rewrite of `AccessorCache` produced no visible benchmark improvement.

OpenTelemetry's own instrumentation model enriches at **semantic boundary methods** (servlet `service`, controller methods, DB-execute) — not data-class getters. This aligns attributes with the span that actually represents the work, and runs enrichment once per request.

This change adopts that model: the team declares the **exit-point method** that closes a logical work unit (`<enrich class method>`), and the agent enriches once at that point, reading properties from a configurable root.

## Goals / Non-Goals

**Goals:**
- One enrich event per span-producing method exit (not per getter).
- Configurable root source: `$this` (receiver), `$argN` (parameter), `$return` (return value).
- XML config simple enough for a Java developer to write without knowing ByteBuddy/advice internals.
- Preserve existing `ValueResolver` segment-walking, `AccessorCache` (incl. LMF), `AttributeConverter`, `SpanWriter`, `FifoCache`, `RuntimeBridge` classloader safety, and isolation/failure-semantics — structural changes only where required.
- Measurable overhead reduction (target: <5% from ~20%).

**Non-Goals:**
- Wildcard / regex method matchers in config (YAGNI; one declared method per `<enrich>`).
- Multiple enrichment points for the **same** `(Class, method)` pair (consolidate rules in one block).
- Runtime path parsing, regex matching, or string-based traversal.
- Map-traversal, static-method enrichment, field-write instrumentation, filters, transforms.
- Automatic config migration (few configs exist; manual rewrite is shorter than writing a tool).
- Backwards compatibility for the old XML schema.

## Decisions

### Decision 1: Instrumentation installs advice on all instance methods; runtime filters via ExitPointIndex

**Choice:** `TraceAttributeTypeInstrumentation.typeMatcher()` matches types whose supertype name appears in `RuntimeBridge.rootClassNames()` (lazily cached by config identity). `transform()` installs advice on every non-static instance method of matching types, split by return type (`void` → `VoidAdvice`, non-`void` → `ValueAdvice`). The runtime `EnrichmentRuntime.enrich` filters non-exit-point calls via `ExitPointIndex.find().isEmpty()` before any span/attribute work.

**Why not "declared method only":** ByteBuddy installs method matchers at agent bootstrap — before `classLoaderMatcher` calls `RuntimeBridge.initialize`, which is the only point where the configured method names become available. A `namedOneOf(methodNames)` matcher at bootstrap sees an empty set and installs no advice. The class is later loaded by `Class.forName` inside the parser, transformed at that moment, and never re-transformed even after the real publish. Installing advice on all instance methods and filtering at runtime is the only approach that survives ByteBuddy's boot-time matcher installation.

**Alternatives considered:**
- *Per-class `TypeInstrumentation` built eagerly from `rootClassMethods()`.* `typeInstrumentations()` is called once at startup before config is loaded → empty list → zero instrumentation. Rejected.
- *Keep per-instance-method instrumentation + dedupe by span identity.* Adds ThreadLocal plumbing, fails on async spans / re-entrant code. Avoided.
- *Re-transform on reload.* ByteBuddy `retransform` is expensive and races with app threads. YAGNI.

**Rationale:** Eliminates the root cause of the 20% overhead (150 `setAttribute` calls per POST → 15). Non-exit-point sibling calls short-circuit in one bounded-cache lookup (O(1) after warmup) — negligible cost. Decouples enrichment from "always the receiver" by letting the team pick where the object lives.

### Decision 2: `RootSource` as a sealed enum-like record, not a string convention

**Choice:** `sealed interface RootSource permits This, Argument, ReturnValue`. Records hold arg index. `ConfigurationParser` recognizes `$this` / `$argN` / `$return` and produces these typed values.

**Alternatives considered:**
- *String prefix + runtime parsing.* Violates the "no runtime DSL" requirement.
- *Lambda/supplier per rule.* Adds JVM class-per-rule overhead. The sealed `RootSource` lets `EnrichmentRuntime` select the root via a `switch` on a small sealed type — JIT compiles to a perfect branch table.

**Rationale:** Type safety at compile time of the configuration. Zero cost on the hot path. Clear error messages at parse time if `$arg` is missing its integer or the index is negative.

### Decision 3: `ExitPointIndex` keyed by `(Class<?>, String methodName)`

**Choice:** Replaces `RuleIndex` (keyed by root `Class<?>` only). Each `ExitPoint` binds `(rootClass, methodName)` to an immutable `List<ExitRule>` (each rule = `key + RootSource + segments`).

**Index lookup on the hot path:**
1. Advice enters with `(receiverClass, invokedMethodName)` — both available from ByteBuddy context without reflection.
2. `ExitPointIndex.find(Class, String) → List<ExitRule>` — two-step: `ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, List<ExitRule>>>` or a single `ConcurrentHashMap<ExitPointKey, List<ExitRule>>` with `record ExitPointKey(Class<?>, String)`. Choose the single-map form: one `record` equality check, simpler, same O(1) semantics.
3. Cache misses: bounded `FifoCache<ExitPointKey, List<ExitRule>>` (1000 entries, FIFO) reused — same pattern as today's `RuleIndex`. Negative results cached (empty list sentinel).

**Alternatives considered:**
- *Two-level map.* More objects, more indirection, no simpler. Rejected.
- *Per-rule index keyed by method only.* Two classes with the same method name (e.g. `handle`) would collide. Rejected.

**Rationale:** Direct, low-allocation lookup keyed by exactly what the advice knows. Preserves the existing bounded-FIFO cache pattern.

### Decision 4: Advice split into VoidAdvice and ValueAdvice

**Choice:** ByteBuddy raises "Cannot assign void to class java.lang.Object" if `@Advice.Return Object` is declared on a `void` method. Two advice classes are installed:

```java
// void methods — no @Advice.Return; $return rules resolve to null at runtime.
public static class VoidAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void onExit(
      @Advice.This Object receiver,
      @Advice.AllArguments Object[] arguments,
      @Advice.Origin Method method) {
    EnrichmentRuntime.enrich(receiver, arguments, null, method.getName());
  }
}

// non-void methods — @Advice.Return Object captures the return value.
public static class ValueAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void onExit(
      @Advice.This Object receiver,
      @Advice.AllArguments Object[] arguments,
      @Advice.Return Object returned,
      @Advice.Origin Method method) {
    EnrichmentRuntime.enrich(receiver, arguments, returned, method.getName());
  }
}
```

The method matcher splits via `returns(void.class)` / `not(returns(void.class))`. `@Advice.Origin Method` provides the invoked method name for `ExitPointIndex.find`.

`@Advice.AllArguments` allocates an `Object[]` per call — acceptable because:
- It's already allocated by the call frame (conceptually); ByteBuddy reuses its own small array, and JIT escape-analyzes it for short-lived advice.
- One allocation per span-production event (the exit point), not per getter call — so overhead is 1/N where N=10 was the previous model.

**Alternatives considered:**
- *Single advice with `@Advice.Return Object`.* ByteBuddy rejects it on `void` methods. Rejected.
- *Reflect on parameter values from ByteBuddy's `MethodDescription`.* More expensive than `@Advice.AllArguments` and wouldn't allow arg indexing on the hot path.

### Decision 5: `ValueResolver` resolves from an arbitrary root object, not always the receiver

**Choice:** `ValueResolver.resolve(Object root, List<PathSegment>)` — identical signature. The caller (`EnrichmentRuntime.write`) computes the root once by switching on `RootSource`:

```java
final Object root =
    switch (rule.rootSource()) {
      case This t -> receiver;
      case Argument a -> a.index() < args.length ? args[a.index()] : null;
      case ReturnValue r -> returned;
    };
```

Then `RESOLVER.resolve(root, rule.segments())`.

**Rationale:** `ValueResolver` already takes an `Object root`. No change to its body. The `AccessorCache` searches by `root.getClass()` — already the case. This is a 1-method edit in `EnrichmentRuntime.write`.

### Decision 6: Config schema — `<enrich class method>` block with `$`-prefixed paths

**Choice:**
```xml
<dynamic>
  <enrich class="com.example.Garage" method="park">
    <attribute key="brand" path="$arg0.brand"/>
    <attribute key="passenger1" path="$arg0.passengers[1].name"/>
    <attribute key="customerName" path="$this.customer.name"/>
  </enrich>
</dynamic>
```

- `<enrich class method>` bundles all rules applied at one exit point — one parse scope, one runtime lookup, one enrich call.
- Paths start with `$this` | `$argN` (N=0..127, decimal, non-negative) | `$return`. Parser validates the prefix at parse time.
- The rest of each path is the existing dot+index grammar (unchanged).
- Subclass overrides: if `<enrich class>` declares `com.example.Garage` and a subclass `SportsGarage extends Garage` overrides `park`, the type matcher (`hasSuperType`) matches `SportsGarage` because its supertype `com.example.Garage` is in `rootClassNames()`. Advice is installed on all instance methods of `SportsGarage` (including the override). Runtime lookup uses the receiver's actual `Class<?>` via `ExitPointIndex.find` — falls back to `isAssignableFrom` lookup if the exact subclass is not in the cache.

**Alternatives considered:**
- *Per-attribute `<attribute class method path>`.* Forces the team to repeat `class`/`method` for every attribute — verbose, error-prone, harder to see "all rules for park".
- *External method-selector DSL.* Breaks the "config is XML only" axiom.

**Rationale:** Bundles rules by where they apply — matches the "one enrich per span" model and reads as a sentence: *"When Garage.park finishes, read these properties and write them on the span."*

### Decision 7: Static attributes stay global, dynamic attributes per-exit-point

**Choice:** `<static>` unchanged; `<dynamic>` becomes a list of `<enrich>` blocks. Static attributes apply to every enrichment event (as today, one write per exit point; previously one write per getter exit). This reduces static `setAttribute` calls from N×5 (where N = enriched methods) to 1×5 per span-producing method — same proportional win as the dynamic path.

### Decision 8: Benchmark app contains a natural exit point (`Garage.park`)

**Choice:** Add a domain method `Garage.park(Car)` to the demo; previous `CarsHandler.handle` reads XML body, calls `garage.park(car)`, responds. Config the agent on `<enrich class="com.example.Garage" method="park">` with `$arg0.brand`, `$arg0.passengers[1].name`, etc. Remove the 10 manual `car.getBrand()..` calls from `CarsHandler.handle`. The benchmark becomes representative of a real handler-with-service app.

## Risks / Trade-offs

- **[Risk] ByteBuddy boot-time matcher constraint.** ByteBuddy installs method matchers at agent bootstrap, before config is loaded. A `named(methodNames)` matcher at that point sees an empty set and installs no advice. → *Mitigation:* advice is installed on all instance methods of matching types; `ExitPointIndex.find` filters at runtime. Pre-publishing `ROOT_NAMES` + `ENABLED=true` before the parser runs ensures the type matcher matches during the nested `Class.forName` inside `ConfigurationParser.parseDynamic`.
- **[Risk] Method overload resolution.** If `<enrich class method="park">` is declared and `Garage` has `park(Car)` and `park(String)`, advice attaches to both (ByteBuddy can't disambiguate by parameter type at install time). → *Mitigation:* explicitly out-of-scope (Non-Goal: wildcard). Document: one method by that name per class; overloads cause advice to attach to all, with `$argN` resolved against whatever arity that overload has. If the team needs a specific overload, rename one method or split to a subclass — typical Java domain code rarely overloads span-producing methods.
- **[Risk] Renaming a method silently invalidates config.** → *Mitigation:* `ConfigurationParser` validates at startup that the declared class resolves and the method name exists (any-arity). Failures disable enrichment for that loader and log one error (existing failure-isolation pattern). Runtime classloader mismatch safety unchanged.
- **[Risk] `$return` on a `void` exit method.** The parser validates the `$return` prefix syntactically but does not know the return type without loading the class (which it does, but only for method-existence checking, not return-type checking). → *Mitigation:* `VoidAdvice` passes `null` for the return value; `$return` rules resolve to `null` at runtime and are skipped. The agent logs nothing here — silently skipping is safe (OTel attributes cannot represent null anyway).
- **[Risk] Multiple `<enrich>` blocks for the same `(class, method)`.** → *Mitigation:* explicitly a Non-Goal. Parser rejects duplicates with a configuration error (one block per `(class, method)`); the team consolidates attributes into a single block.
- **[Risk] Subclass lookup at runtime.** Receiver class may be a not-yet-indexed subclass of the declared `<enrich class>`. → *Mitigation:* `ExitPointIndex.find` uses the existing `FifoCache` pattern — first exact `(Class, name)` lookup; on miss, walk configured `ExitPoint`s matching `rootClass.isAssignableFrom(actualClass)` AND `methodName.equals(invokedName)`. Frozen after compilation. Same semantics as today's `RuleIndex.applicable(Class)`.
- **[Risk] Argument array allocation cost.** `@Advice.AllArguments` allocates an `Object[]` per call. → *Mitigation:* one allocation per span-producing exit (vs N per getter under the old model). Net win even if escape analysis fails. Non-exit-point calls also allocate but short-circuit immediately after `ExitPointIndex.find`.
- **[Risk] Parse failure clears global ROOT_NAMES.** If `RuntimeBridge.initialize` catches a `ConfigurationException` for one loader and clears `ROOT_NAMES`, other loaders that successfully published would lose their type-matcher data. → *Mitigation:* the catch block rolls back `ENABLED` to `false` for the failed loader only; it MUST NOT clear the global `ROOT_NAMES`.
- **[Risk] Zombie processes on bench ports.** A prior `bench.sh` run or a manually started app may still hold port 8081, 4318, or 14317, causing "Address already in use" and a silent mid-bench abort. → *Mitigation:* `bench.sh` calls `free_required_ports` before `start_stub` in both benchmark and verify phases; `stop_app` force-frees the port if `wait_port_free` times out.
- **[Trade-off] Breaking XML config.** Few configs in this repo, no external consumers — acceptance threshold is low. Migration is a search-and-replace of `<dynamic>` content, not the whole document.

## Migration Plan

1. **Rewrite `<dynamic>` section** of every config from per-attribute root-class paths to one `<enrich>` block per exit-point method. The `<static>` section is untouched.
2. **Add domain exit-point methods** in the application where natural ones don't already exist (e.g. `Garage.park(Car)`, `OrderService.submit(Order)`). For typical service/handler architectures, the method already exists and only the config changes.
3. **Validation at startup**: the parser fails loudly (one ERROR log) if the declared method does not exist on the declared class. Fix the typo / rename and restart — no partial instrumentation.

**Rollback strategy:** revert to the previous commit; no persistent schema state to migrate back. The agent reads config fresh each start.

## Open Questions

1. **Overload disambiguation.** Out-of-scope today. If a real-world class has `handle(String)` and `handle(Request)` and only the second should be enriched, what's the least-bad answer? Proposal: add optional `<enrich class method signature="com.example.Request">` later if the need arises — YAGNI until then.
2. **Async span producers.** If the exit-point method spawns a thread and returns before the span ends, the active span at exit may be a parent span. Out of scope — same limitation applies to all OTel method-exit enrichment.
3. **Multiple spans from one exit point.** If the exit method starts and ends multiple child spans itself before returning, attributes land on `Span.current()` at exit — which is whichever span was made current last. Out of scope; conventional advice is to enrich the boundary that owns the span you intend to enrich.

This change deliberately narrows scope to make the common case fast and correct.