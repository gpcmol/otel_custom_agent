## 1. Configuration model & parser

- [x] 1.1 Add `RootSource` sealed interface (`THIS`, `Argument(int index)`, `ReturnValue`) in `config/model/`
- [x] 1.2 Add `ExitPoint` record (`Class<?> rootClass`, `String rootClassName`, `String methodName`, `List<ExitRule> rules`) in `config/model/`
- [x] 1.3 Add `ExitRule` record (`String key`, `RootSource rootSource`, `List<PathSegment> segments`) in `config/model/`; replace `DynamicAttributeRule`
- [x] 1.4 Update `CompiledConfiguration` to expose `List<ExitPoint>` instead of `List<DynamicAttributeRule>`
- [x] 1.5 Extend `ConfigurationParser.parseDynamic` to read `<enrich class method>` blocks (one `ExitPoint` per block, child `<attribute key path>` parsed into `ExitRule`s)
- [x] 1.6 Parse the `$this`, `$argN`, `$return` path prefix into a `RootSource` before delegating the remaining segments to the existing path-parsing logic; reject missing or unknown `$` tokens and invalid `$argN` indices
- [x] 1.7 Reject duplicate `<enrich class method>` pairs within one config
- [x] 1.8 At parse time, `Class.forName` the declared class and verify at least one method with the declared name exists; on failure, emit a single configuration error naming the `(class, method)` pair
- [x] 1.9 Reject the previous flat `<dynamic><attribute path="com.example.Car.brand"/></dynamic>` form with a configuration error mentioning the new `<enrich>` requirement

## 2. Runtime model & index

- [x] 2.1 Add `record ExitPointKey(Class<?> type, String methodName)` in `runtime/model/`
- [x] 2.2 Replace `RuleIndex` with `ExitPointIndex` keyed by `ExitPointKey`; expose `find(Class, String) → List<ExitRule>` using `FifoCache<ExitPointKey, List<ExitRule>>` (bound 1000, FIFO) with empty-list sentinel for negative results
- [x] 2.3 Implement miss path: walk configured `ExitPoint`s, match where `declared.rootClass.isAssignableFrom(actualClass)` AND `declared.methodName.equals(invokedName)`
- [x] 2.4 Remove old `DynamicAttributeRule`-based `RuleIndex.applicable(Class)`; delete `RuleIndex` if no longer referenced
- [x] 2.5 Update `RuntimeState` to hold `ExitPointIndex` instead of `RuleIndex`

## 3. Runtime enrichment

- [x] 3.1 Change `EnrichmentRuntime.enrich(Object receiver)` to `enrich(Object receiver, Object[] arguments, Object returned)` — receiver may be null for static exit-point methods (skip in that case)
- [x] 3.2 In `write`, compute the rule root via `switch` on `RootSource`: `THIS → receiver`, `Argument(i) → i < args.length ? args[i] : null`, `ReturnValue → returned`
- [x] 3.3 Resolve `ExitPointIndex.find(receiver.getClass(), invokedMethodName)`; if empty, return without further work
- [x] 3.4 The invoked method name must reach `enrich` — pass it as a parameter from the advice (see 4.3); thread it through `write` to the `ExitPointIndex.find` call
- [x] 3.5 Iterate the resolved `ExitRule`s once per enrich call: resolve root → `ValueResolver.resolve(root, segments)` → `AttributeConverter.convert` → `SpanWriter.write`; failures per-rule isolated, others continue
- [x] 3.6 Static cache reset condition unchanged (`state != cachedState`); static writes still happen once per enrich event
- [x] 3.7 Keep current-span lookup (`Span.current()`) once per enrich event; keep fallback-span creation path unchanged
- [x] 3.8 Reentrancy guard (`ThreadLocal<Boolean> ENRICHING`) keeps its current semantics — one guard per enrich call

## 4. Instrumentation

- [x] 4.1 Update `TraceAttributeTypeInstrumentation.typeMatcher()` to match the union of `<enrich class>` types from the active config (cached by root-name-set identity as today)
- [x] 4.2 Update `transform(TypeTransformer)` method matcher to install advice on all non-static instance methods (ByteBuddy installs matchers at bootstrap before config is loaded; `ExitPointIndex.find` filters at runtime). Split by return type: `returns(void.class)` → `VoidAdvice`, `not(returns(void.class))` → `ValueAdvice`.
- [x] 4.3 Split `TraceAttributeAdvice` into `VoidAdvice` (void methods, no `@Advice.Return`) and `ValueAdvice` (non-void, with `@Advice.Return Object`); both receive `@Advice.This`, `@Advice.AllArguments`, and `@Advice.Origin Method` for the method name. ByteBuddy raises "Cannot assign void to Object" if `@Advice.Return Object` is on a void method — the split avoids that.
- [x] 4.4 Adjust `getAdditionalHelperClassNames()` (in `TraceAttributeInstrumentationModule`) to include `RootSource` and its permitted subtypes, `ExitPoint`, `ExitRule`, `ExitPointKey`, `ExitPointIndex`, `VoidAdvice`, `ValueAdvice` (replacing `TraceAttributeAdvice`)
- [x] 4.5 Verify the helper-class list stays minimal (no app classes forced into the agent loader)

## 5. ValueResolver & AccessorCache (minimal change)

- [x] 5.1 Confirm `ValueResolver.resolve(Object root, List<PathSegment> segments)` already accepts any non-null root — no signature change needed; verify behaviour when root is an argument object rather than a receiver (paths like `$arg0.passengers[1].name` must traverse correctly)
- [x] 5.2 `AccessorCache` already keys by `(root.getClass(), propertyName)` — no change; LMF lambdas from the previous change keep working
- [x] 5.3 Add a `ValueResolverTest` case resolving a path from a parameter-derived root (not the receiver)

## 6. Benchmark app refactor

- [x] 6.1 Add `Garage.park(Car car)` domain method to `app/src/main/java/com/example/Garage.java` that records the car (in-memory or delegate to existing storage)
- [x] 6.2 Update `Main.CarsHandler.handle` to call `garage.park(car)` once for `POST /cars`; remove the 10 explicit `car.getBrand()..car.getPassengers()` getter calls
- [x] 6.3 Rebuild the Base64 config in `scripts/bench.sh` to the new schema: one `<enrich class="com.example.Garage" method="park">` block with 10 `$arg0.<property>` rules (incl. `$arg0.passengers[1].name`) and 5 static rules
- [ ] 6.4 Confirm the benchmark `verify` phase (`scripts/bench.sh verify_attributes`) still finds all configured attributes in telemetry.log

## 7. Tests

- [x] 7.1 Update `ConfigurationParserTest` for the new XML schema (valid `<enrich>` blocks, missing `class`/`method`, duplicate exit points, legacy flat form rejection, `$this`/`$argN`/`$return` prefixes, invalid `$arg`, missing/unknown `$` prefix)
- [x] 7.2 Add `ExitPointIndexTest` (exact-class lookup hit, subclass `isAssignableFrom` match, missing method → empty sentinel, FIFO bound respected)
- [x] 7.3 Update `ValueResolverTest` (root-from-argument, root-from-return, root-from-receiver still works)
- [ ] 7.4 Add `EnrichmentRuntimeTest` for `enrich(receiver, args, return)` — single enrich event, all attributes written once each, `Span.current()` mocked once per call
- [ ] 7.5 Update `IntegrationTest` (javaagent smoke test) to use the new config and assert attributes still appear on exported spans
- [x] 7.6 Update `ConfigurationValidationTest` for new validation rules
- [x] 7.7 Run `./gradlew test` and ensure full suite is green

## 8. Lint, type check, detect_changes

- [x] 8.1 Run `./gradlew lint` (or equivalent configured check) and resolve violations
- [x] 8.2 Run `./gradlew compileJava compileTestJava` for type-check
- [x] 8.3 Run `gitnexus_detect_changes({scope: "unstaged"})` and confirm only expected symbols / processes are affected
- [x] 8.4 Impact-analyze renamed symbols (e.g. `RuleIndex` → `ExitPointIndex`) with `gitnexus_impact` prior to the rename; use `gitnexus_rename` for cross-repo refactors

## 9. Benchmark validation

- [ ] 9.1 Build artifacts: `./gradlew extendedAgent` and `mvn package -DskipTests` in `app/`
- [ ] 9.2 Run `scripts/bench.sh --mode all` end-to-end
- [ ] 9.3 Compare enabled-vs-disabled throughput overhead to the previous 20.4% baseline; document the new overhead in the markdown report
- [ ] 9.4 Run `scripts/bench.sh --mode verify` to confirm attribute correctness is preserved

## 10. Documentation

- [x] 10.1 Update `README.md` configuration section with the new `<enrich class method>` schema and `$this`/`$argN`/`$return` path-root syntax
- [x] 10.2 Add a short migration note for teams moving from the old flat `<dynamic>` schema (one paragraph)
- [x] 10.3 Update the design comment in `TraceAttributeTypeInstrumentation` class Javadoc to reflect exit-point semantics instead of per-instance-method instrumentation

## 11. Benchmark script robustness

- [x] 11.1 Add `free_port <port>` helper to `scripts/bench.sh` — `lsof -t` finds listeners, SIGTERM, 1s wait, SIGKILL if needed; idempotent and `set -e`-safe via `|| true`
- [x] 11.2 Add `free_required_ports` that frees `STUB_PORT` (4318) and `APP_PORT` (8081)
- [x] 11.3 Call `free_required_ports` before `start_stub` in both the `all|enabled|disabled` block and the `all|verify` block
- [x] 11.4 Harden `stop_app`: `wait_port_free "$APP_PORT" || free_port "$APP_PORT"` — prevents silent `set -e` abort between enabled and disabled phases when a dying JVM is slow to release the port
