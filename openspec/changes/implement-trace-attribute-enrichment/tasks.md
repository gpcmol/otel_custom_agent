## 1. Project Discovery and Runtime Boundary

- [x] 1.1 Inspect the OpenTelemetry Java Instrumentation project setup and selected local build/API version, using `https://github.com/open-telemetry/opentelemetry-java-instrumentation/tree/main` as reference; identify the extension module, package root, Byte Buddy integration, tracer access, logging API, service registration convention, and test framework.
- [x] 1.2 Establish Java 21/Gradle project setup with package root `org.otel`, following the OTel extension example layout and selected agent API version.
- [x] 1.3 Add the minimal package structure and agent-to-application-classloader runtime bridge without adding dependencies or a second agent bootstrap mechanism.
- [x] 1.4 Apply the defined Java code style during implementation: small focused methods, single-responsibility classes, simple control flow, small signatures, minimal abstractions, and concise comments.
- [ ] 1.5 Apply Java 21 quality rules: SonarLint-clean code, zero SpotBugs warnings, SOLID without speculative abstractions, practical immutability, correct Optional usage, records/pattern matching where clear, no duplication, try-with-resources, explicit final variables/parameters, no `var`, modern collection methods, and clear text-block/String.format string construction.
- [ ] 1.6 Define and test the loader-neutral configuration snapshot and per-application-classloader runtime-state lifecycle; verify failed loader initialization cannot overwrite another loader's successful state.

## 2. Configuration and Compiled Model

- [x] 2.1 Implement exact `OTEL_CUSTOM_AGENT_CONFIG` lookup, missing/blank handling, standard Base64 decoding, strict UTF-8 decoding, and secure JDK XML parsing with external access disabled.
- [x] 2.2 Implement exact XML structure validation for optional sections, attribute-only children, unknown names/attributes, namespaces, required values, case-sensitive OpenTelemetry keys, duplicate keys, and value-whitespace preservation.
- [x] 2.3 Implement the deterministic path grammar, longest loadable root-class prefix resolution, indexed-property validation, and immutable `PropertySegment`/`IndexedPropertySegment` equivalents.
- [x] 2.3a Verify the documented `Car`, nested `passengers`, and indexed `Garage.customers[0].city` paths, including package and nested-class binary-name boundaries.
- [x] 2.4 Implement immutable static/dynamic rule models with no original path string and atomic startup state publication, including quiet missing-config and concise disabled/error states.
- [x] 2.5 Build the startup-only class-based dynamic rule index with `isAssignableFrom` subclass matching, classloader identity, bounded runtime-class lookup, and no per-exit all-rule scan.
- [x] 2.6 Implement FIFO-bounded accessor and applicable-rule caches with a maximum of 1000 entries each.

## 3. Runtime Resolution and Telemetry

- [x] 3.1 Implement public getter-first (`getX`), public boolean (`isX`), and normally accessible exact-field fallback accessors without `setAccessible`, with invocation isolation, negative lookup caching, and classloader-safe keys.
- [x] 3.2 Implement finite nested traversal for ordinary objects, arrays, `List`, `Collection`, and `Iterable`, including terminal/nested collection flattening, ordering, null omission, empty collections, indexed access without full iterable copies, map rejection, and finite-path cycle safety.
- [x] 3.3 Implement conversion to typed OpenTelemetry scalar and homogeneous array attributes: strings/enums, booleans, integral/floating numbers, primitive-array copies, mixed-type rejection, and null/unsupported skipping without `toString`.
- [x] 3.3a Add safe scalar mappings for `Character`, `Byte`, `Short`, fitting `BigInteger`, and finite `BigDecimal`.
- [x] 3.4 Implement current-span enrichment and exactly-one ended `INTERNAL` fallback span behavior with static-first application, per-rule failure isolation, best-effort cleanup, unchanged application exceptions/returns, and one warning when fallback creation fails.

## 4. Instrumentation

- [x] 4.1 Implement an OpenTelemetry `InstrumentationModule` following `DemoServlet3InstrumentationModule`, including supported module discovery/registration and `TypeInstrumentation` ownership.
- [x] 4.2 Implement `TypeInstrumentation` following `DemoServlet3Instrumentation`, using the selected `TypeTransformer.applyAdviceToMethod` API and method-exit Advice for configured root types and `instanceof`-style subclasses.
- [x] 4.3 Add documented match exclusions for agent/JDK/OpenTelemetry/runtime infrastructure, interfaces, synthetic methods, null receivers, disabled state, and duplicate registration.
- [x] 4.4 Wire instrumentation to a small runtime bridge without eagerly loading application model classes from the agent classloader and without retaining unbounded application objects/loaders.
- [ ] 4.5 Register every Advice and transitive runtime helper class, including nested helper classes, through the OTel helper-class mechanism; verify injected Advice can resolve the full runtime graph.
- [ ] 4.6 Add a re-entrancy guard so instrumented getters/serialization/telemetry cannot recursively enrich the same event.

## 5. Verification

- [x] 5.1 Add configuration tests for valid sections, missing/blank config, strict Base64/UTF-8, malformed XML/path, unknown structure/namespaces, required attributes, duplicate/invalid keys, unresolved roots, secure XML, immutable compiled model, and no partial registration.
- [x] 5.2 Add resolver tests for public getter precedence, public boolean getter, normally accessible field fallback, no `setAccessible`, nested values, primitive/boxed/string/enum values, `Character`/`Byte`/`Short`/`BigInteger`/`BigDecimal`, arrays/lists/collections/iterables, terminal/nested flattening, indexed access, empty/null values, exceptions/inaccessible members, maps, mixed values, out-of-range indexes, cycle bounds, and typed OTel values.
- [ ] 5.3 Add instrumentation and telemetry tests for module discovery, the `InstrumentationModule`/`TypeInstrumentation` split, `TypeTransformer` advice installation, configured-only matching, subclass/`instanceof`, separate classloaders, current/fallback spans, static/dynamic writes, one broken rule, unchanged application behavior, runtime isolation, and no duplicate advice.
- [ ] 5.3a Add a transformed-application test proving Advice execution, helper injection, loader-neutral handover, per-loader class identity, and no recursive enrichment.
- [ ] 5.4 Add concurrency, FIFO cache warm-up/negative-cache and 1000-entry eviction tests, atomic startup, secure classloader boundary, startup count/status logging, no sensitive config logging, no normal runtime logs, fallback-creation warning, no fallback allocation with current span, and performance-path tests; run the standard project verification command.
- [ ] 5.9 Run SonarLint/static-analysis and SpotBugs checks, fix all findings, verify no production `var` usage, verify explicit final locals/parameters, and run the complete Gradle quality/test command.
- [ ] 5.5 Add `IntegrationTest` and `OkHttpUtils` following the official OTel extension smoke-test pattern, including bounded HTTP clients, Testcontainers network/backend lifecycle, target lifecycle, trace polling, and protobuf span assertion helpers.
- [ ] 5.6 Add a concrete `SpringBootIntegrationTest`-style smoke test with `Car`, `Passenger`, and `Garage`, asserting application response plus static, nested dynamic, and indexed dynamic attributes from exported traces.
- [ ] 5.6a Ensure the smoke-test model uses public JavaBean getters; record component accessors alone MUST NOT be used to validate getter-based resolution.
- [x] 5.7 Configure Shadow and extended-agent packaging plus Gradle system properties for agent, extension, and extended-agent paths; verify extension-jar, extension-directory, and embedded-extension loading modes where available.
- [ ] 5.8 Make Docker-dependent smoke tests safely skippable when Docker or the target image is unavailable, while keeping all unit/parser/resolver tests runnable without Docker.
> Status: superseded. The original startup-source tasks describe removed
> Base64 environment configuration.
