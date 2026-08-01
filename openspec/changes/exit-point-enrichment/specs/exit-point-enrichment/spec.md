## ADDED Requirements

### Requirement: Exit-point enrichment SHALL enrich at one declared method per span

The team declares the exit point via `<enrich class method>` in the `<dynamic>` section. ByteBuddy installs method matchers at agent bootstrap — before `RuntimeBridge.initialize` publishes the configured exit-point method names via `classLoaderMatcher`. As a result, advice is installed on every non-static instance method of matching types (those whose supertype name appears in the configured exit-point classes), not on declared methods only.

To compensate, `EnrichmentRuntime.enrich` MUST look up the applicable `ExitRule`s for `(receiver.getClass(), invokedMethodName)` via `ExitPointIndex.find` at runtime. When the `(class, methodName)` pair matches no configured exit point, the index returns an empty-list sentinel and the enrich call MUST short-circuit before fetching the current span, writing any attribute, or creating a fallback span — the cost is one bounded-cache lookup (O(1) after warmup).

The agent MUST call `EnrichmentRuntime.enrich` at most once per method invocation. For a configured exit-point method, enrichment MUST run exactly once. For a non-exit-point sibling method, `ExitPointIndex.find` MUST return an empty list and MUST NOT perform any span or attribute work.

#### Scenario: One enrich call per declared method exit
- **WHEN** the configured exit-point method is invoked once
- **THEN** the agent MUST call enrichment exactly once for that invocation
- **AND** attribute writes MUST total the number of configured static + dynamic rules

#### Scenario: Non-exit-point sibling methods short-circuit
- **WHEN** the configured class has methods not named in any `<enrich>` block
- **THEN** those methods MUST enter the advice and call `ExitPointIndex.find`
- **AND** `find` MUST return an empty-list sentinel
- **AND** the advice MUST short-circuit without fetching a span, writing attributes, or creating a fallback

#### Scenario: Subclass overrides also enrich
- **WHEN** `<enrich class="com.example.Garage" method="park">` is configured
- **AND** a subclass `SportsGarage extends Garage` overrides `park`
- **THEN** the override MUST also receive advice (via `hasSuperType`)
- **AND** the same configured attributes MUST be applied when the subclass override exits

### Requirement: Path roots SHALL use a typed RootSource model

The agent MUST resolve each configured attribute path from a typed `RootSource` anchor: `$this` (the receiver), `$argN` (the Nth parameter, 0-indexed, decimal, non-negative), or `$return` (the method return value). The parser MUST accept only these three prefixes and MUST reject paths that omit the prefix or use an unknown `$` token. The parser MUST validate that `$argN` has a valid non-negative decimal integer N not exceeding 127.

The root object resolution MUST be a sealed-interface switch (`THIS`, `Argument(index)`, `ReturnValue`) compiled at parse time; the hot path MUST NOT string-parse the prefix.

For `Argument(index)` where the actual arity at the exit point is less than `index`, the root MUST resolve to `null` and the attribute MUST be skipped without error. For `ReturnValue` on a `void` exit method, the root MUST resolve to `null` and the attribute MUST be skipped without error.

#### Scenario: `$this` resolves to the receiver
- **WHEN** an attribute path starts with `$this`
- **THEN** the resolver MUST read the receiver instance as the root

#### Scenario: `$argN` resolves to the Nth parameter
- **WHEN** an attribute path starts with `$arg2` and the exit-point method has at least 3 parameters
- **THEN** the resolver MUST read the third parameter (index 2) as the root
- **AND** resolution MUST proceed normally from that root

#### Scenario: `$argN` out of range resolves to null
- **WHEN** an attribute path starts with `$arg5` and the exit-point method has 3 parameters
- **THEN** the root MUST resolve to null
- **AND** the attribute MUST be skipped without raising an error

#### Scenario: `$return` resolves to method return value
- **WHEN** an attribute path starts with `$return` and the exit-point method returns a non-void value
- **THEN** the resolver MUST read the return value as the root

#### Scenario: `$return` on void resolves to null
- **WHEN** an attribute path starts with `$return` and the exit-point method is void
- **THEN** the root MUST resolve to null
- **AND** the attribute MUST be skipped without raising an error

#### Scenario: Missing or unknown root prefix is rejected
- **WHEN** a path does not start with `$this`, `$arg`, or `$return`
- **OR** a path starts with `$` followed by any other token
- **THEN** startup MUST reject the configuration with a configuration error

#### Scenario: `$arg` without a decimal index is rejected
- **WHEN** a path starts with `$arg` not followed by a non-negative decimal integer
- **THEN** startup MUST reject the configuration

### Requirement: Dynamic rules SHALL be grouped into ExitPoint records

Each `<enrich class method>` block MUST compile to an immutable `ExitPoint` record binding `(rootClass, methodName)` to an immutable list of `ExitRule` records. Each `ExitRule` MUST contain the attribute `key`, the typed `RootSource`, and the compiled immutable `List<PathSegment>` (reusing the existing `PropertySegment` / `IndexedPropertySegment` grammar). `ExitPoint` instances MUST be immutable and safely publishable between threads.

The agent MUST reject duplicate `<enrich>` blocks for the same `(class, method)` pair at parse time. The team MUST consolidate all attributes for one exit point into a single block.

#### Scenario: Attribute rules are grouped by exit point
- **WHEN** a config declares `<enrich class="com.example.Garage" method="park">` with three `<attribute>` children
- **THEN** the compiled model MUST contain exactly one `ExitPoint` with `(com.example.Garage.class, "park")`
- **AND** that ExitPoint MUST bind an immutable list of three `ExitRule` records

#### Scenario: Duplicate exit-point blocks are rejected
- **WHEN** two `<enrich>` blocks share the same `(class, method)` pair
- **THEN** startup MUST reject the configuration with a configuration error
- **AND** no partial instrumentation MUST be registered

### Requirement: An ExitPointIndex SHALL provide bounded cached lookup by `(Class, methodName)`

The agent MUST build an `ExitPointIndex` at startup keyed by `record ExitPointKey(Class<?> type, String methodName)`. The index MUST resolve a `List<ExitRule>` for the receiver's actual runtime class and the invoked method name in O(1) on cache hit. On miss, the index MUST walk all configured `ExitPoint`s and select those whose declared class `isAssignableFrom` the runtime class and whose declared method name equals the invoked one. Negative results MUST be cached as an empty-list sentinel, identical to the existing `RuleIndex`/`AccessorCache` pattern.

The index cache MUST be bounded to 1000 entries with FIFO eviction (reusing `FifoCache`). The index MUST be immutable after startup; reload MUST build a new index atomically replacing the old one.

#### Scenario: Exact-class lookup hits the cache
- **WHEN** the same `(receiverClass, methodName)` pair is queried twice
- **THEN** the second lookup MUST hit the cache
- **AND** it MUST NOT walk the configured exit-point list

#### Scenario: Subclass resolves to the parent's exit point
- **WHEN** the receiver is `SportsCar extends Car` and the config declares `<enrich class="com.example.Car" method="produce">`
- **THEN** the index MUST return the Car-declared `ExitRule` list for the SportsCar runtime class

#### Scenario: Unconfigured method returns empty sentinel
- **WHEN** a `(receiverClass, methodName)` pair matches no configured exit point
- **THEN** the index MUST return an empty-list sentinel
- **AND** the sentinel MUST be cached to prevent repeated walks

### Requirement: Instrumentation advice SHALL receive receiver, arguments, and return value

ByteBuddy refuses to install `@Advice.Return Object` on `void` methods ("Cannot assign void to class java.lang.Object"). The agent MUST therefore install two advice classes:

- **VoidAdvice** for `void` methods: `@Advice.OnMethodExit(suppress = Throwable.class)` with `@Advice.This`, `@Advice.AllArguments`, and `@Advice.Origin Method`. The return value is passed as `null` to `EnrichmentRuntime.enrich`. `$return`-rooted rules resolve to `null` and are skipped at runtime.
- **ValueAdvice** for non-`void` methods: same annotations plus `@Advice.Return Object`. The return value is forwarded to `EnrichmentRuntime.enrich`.

Both advices MUST delegate to `EnrichmentRuntime.enrich(receiver, arguments, returnedValue, methodName)` which selects the applicable `ExitPointIndex` rows for `(receiver.getClass(), invokedMethodName)` and resolves each rule's root via the typed `RootSource` switch.

The advice MUST NOT execute when enrichment is disabled or the receiver is null. The advice MUST NOT alter the application's return value, thrown exception, or receiver state. The agent MUST expose all helper classes required by the advice through the established OTel helper-class mechanism.

#### Scenario: Void method advice omits the return value
- **WHEN** a `void` exit-point method completes
- **THEN** the advice MUST call `enrich` with `null` for the return value
- **AND** `$return`-rooted rules MUST resolve to `null` and be skipped

#### Scenario: Non-void method advice forwards the return value
- **WHEN** a non-`void` exit-point method completes
- **THEN** the advice MUST receive the return value via `@Advice.Return`
- **AND** `$return`-rooted rules MUST read from that value

#### Scenario: Advice has access to receiver and arguments
- **WHEN** an exit-point method completes
- **THEN** the advice MUST receive the receiver via `@Advice.This`
- **AND** MUST receive all parameters via `@Advice.AllArguments`

#### Scenario: Advice preserves application behavior
- **WHEN** an exit-point method throws or returns a value
- **THEN** the original exception or return value MUST be preserved
- **AND** no enrichment exception MUST escape into the application

#### Scenario: Advice is suppressed on all errors
- **WHEN** any exception occurs inside the advice
- **THEN** the `suppress = Throwable.class` attribute MUST swallow it
- **AND** application behavior MUST remain unchanged

### Requirement: The parser SHALL validate exit-point method existence at startup

The parser MUST, after compiling each `ExitPoint`, resolve the declared class via `Class.forName(className, false, applicationLoader)` and verify that at least one method with the declared name exists on that class (any parameter arity). A missing class or missing method MUST produce a single configuration error including the `(class, method)` pair, disable enrichment for that loader, and register no partial instrumentation. The parser MUST NOT require a parameter signature in the config — overload disambiguation is out-of-scope (YAGNI).

The `Class.forName` call triggers ByteBuddy's class-load hook: the type matcher is consulted for `com.example.Garage` and, if it matches, advice is installed. For this to work, `RuntimeBridge.initialize` MUST pre-publish `ROOT_NAMES` (the set of configured exit-point class names) and set `ENABLED=true` for the application classloader **before** the parser runs. Without this pre-publish, the type matcher returns `false` (roots empty), ByteBuddy skips the transformation, and the class — already loaded by `Class.forName` — is never re-transformed even after the real publish later.

The pre-publish MUST be derived from a lightweight XML scan of the `<enrich class method>` elements (without full path compilation). If the full parse later fails, the catch block MUST roll back `ENABLED` to `false` for that loader but MUST NOT clear the global `ROOT_NAMES` — another loader may have published successfully, and clearing would break the type matcher for all subsequent loads.

#### Scenario: Declared method exists
- **WHEN** the config declares `<enrich class="com.example.Garage" method="park">` and `Garage` has a `park` method
- **THEN** the parser MUST compile the ExitPoint
- **AND** instrumentation MUST be registered for that method

#### Scenario: Declared class is missing
- **WHEN** the config declares a class that cannot be `Class.forName`-loaded
- **THEN** the parser MUST emit a configuration error naming the class
- **AND** enrichment MUST be disabled for that loader

#### Scenario: Declared method is missing
- **WHEN** the config declares `<enrich class="com.example.Garage" method="unknown">`
- **THEN** the parser MUST emit a configuration error naming the `(class, method)` pair
- **AND** enrichment MUST be disabled for that loader

#### Scenario: ROOT_NAMES is pre-published before Class.forName
- **WHEN** `RuntimeBridge.initialize` is called with a valid `OTEL_CUSTOM_AGENT_CONFIG`
- **THEN** `ROOT_NAMES` and `ENABLED` MUST be set before `ConfigurationParser.parse` runs
- **AND** the first `Class.forName` triggered by the parser MUST see non-empty `ROOT_NAMES`
- **AND** the type matcher MUST match the configured class and install advice

#### Scenario: Parse failure rolls back per-loader without clearing global ROOT_NAMES
- **WHEN** `ConfigurationParser.parse` throws a `ConfigurationException`
- **THEN** `ENABLED` for that loader MUST be set to `false`
- **AND** the global `ROOT_NAMES` MUST NOT be cleared
- **AND** any other loader that successfully published MUST continue to match its configured classes

### Requirement: Enrichment runtime SHALL write each attribute once per enrich event

The runtime MUST iterate the applicable `ExitRule` list once per enrich call, resolve each rule's root, walk the segments, convert the value, and write to the current span — without re-running for the same exit event. Static attributes MUST be written once per enrich event, not once per resolved dynamic attribute. The runtime MUST resolve the current span once per enrich event, not once per attribute.

Static-cache rebuilding (`staticKeys`/`staticValues` arrays) MUST continue to use the existing identity-based rebuild pattern (rebuild only when `RuntimeState` identity changes).

#### Scenario: Static attributes are written once per enrich event
- **WHEN** an enrich event applies 5 static and 10 dynamic rules
- **THEN** each static attribute MUST be written exactly once to the current span
- **AND** each dynamic attribute MUST be written exactly once
- **AND** `Span.setAttribute` calls MUST total 15, not 150

#### Scenario: One current-span lookup per event
- **WHEN** an enrich event processes N rules
- **THEN** `Span.current()` MUST be queried exactly once
- **AND** all attribute writes MUST target the same span reference

### Requirement: Benchmark application SHALL provide a natural exit point

The benchmark application MUST expose a domain method (e.g., `Garage.park(Car car)`) that the agent instruments as the exit point. The benchmark's HTTP handler MUST NOT manually invoke configured getters solely to trigger enrichment (the previous artificial `car.getBrand(); … car.getPassengers();` block MUST be removed). The benchmark metric (enabled vs disabled throughput overhead) MUST demonstrate the runtime overhead reduction from the exit-point model.

#### Scenario: Handler delegates to a domain method
- **WHEN** the benchmark handler receives a POST /cars
- **THEN** it MUST call `garage.park(car)` once
- **AND** the agent MUST enrich at `park`'s exit
- **AND** the handler MUST NOT call individual `car.getX()` methods to force enrichment

#### Scenario: Benchmark overhead shows reduction
- **WHEN** the configured benchmark runs with 5 static + 10 dynamic rules
- **THEN** the enabled-vs-disabled throughput overhead MUST be measurably lower than the previous per-method model's 20% baseline
- **AND** no regression in correctness MUST be introduced (attributes still appear on exported spans)

### Requirement: Benchmark script SHALL free required ports before starting

The benchmark script (`scripts/bench.sh`) MUST free all ports it requires — the OTLP stub port, the application port, and the agent's embedded config-webserver port (14317) — before starting any subprocess. A `free_port` helper MUST use `lsof -t` to find listening PIDs, send SIGTERM, wait 1 second, and if the port is still held, send SIGKILL. The pre-flight MUST be idempotent: if no process holds the port, the helper MUST return immediately without error.

The script MUST call this pre-flight before both the enabled/disabled benchmark phases and the verify phase. If a prior run (or a manually started app) left a process on any required port, the script MUST kill it rather than fail silently with "Address already in use".

#### Scenario: Zombie app on the app port is killed before bench starts
- **WHEN** a prior JVM is still listening on port 8081 when `bench.sh` starts
- **THEN** the pre-flight MUST kill that JVM via SIGTERM (or SIGKILL if SIGTERM is insufficient)
- **AND** the bench MUST proceed to start its own app

#### Scenario: Free port is a no-op when nothing is listening
- **WHEN** no process holds any required port
- **THEN** `free_port` MUST return 0 without logging a kill message

#### Scenario: Stubborn process receives SIGKILL
- **WHEN** a process does not exit within 1 second of SIGTERM
- **THEN** the helper MUST send SIGKILL
- **AND** log that SIGKILL was used

### Requirement: Stop-app SHALL force-free the port if graceful wait fails

After killing the benchmark app, `stop_app` MUST wait for the app port to become free (via the existing `wait_port_free` poll). If that poll times out, `stop_app` MUST call `free_port` to force-kill any remaining listener rather than letting `set -e` abort the script silently between the enabled and disabled phases.

#### Scenario: Slow JVM shutdown is force-killed
- **WHEN** the killed JVM does not release port 8081 within 20 seconds
- **THEN** `stop_app` MUST call `free_port` to force-kill the process
- **AND** the script MUST continue to the next phase