## Context

The repository contains the baseline contract in
`openspec/specs/trace-attribute-enrichment/spec.md`, but no application
implementation is present yet. The change is a Java OpenTelemetry Java Agent
extension with startup-only configuration, runtime receiver inspection, and
method-exit instrumentation. It must work inside the agent classloader model,
avoid new dependencies, and never affect application behavior when enrichment
fails.

Project setup SHALL follow the structure and conventions of the OpenTelemetry
Java Instrumentation repository:
`https://github.com/open-telemetry/opentelemetry-java-instrumentation/tree/main`.
Instrumentation SHALL use the official extension examples as API references:
`DemoServlet3InstrumentationModule.java` and
`DemoServlet3Instrumentation.java`.

The initial project setup MUST use Java 21, Gradle, and the extension-module
layout demonstrated by the OTel example. The implementation package root MUST
start with `org.otel`.

The XML configuration is deliberately unchanged. Developers continue to use
the documented Base64-encoded XML as the only declarative interface; path
compilation is an internal parser concern and MUST NOT become a new public
DSL.

## Goals / Non-Goals

**Goals:**

- Build an immutable compiled configuration once at startup.
- Keep XML parsing and validation outside the runtime traversal path.
- Resolve configured rules by class identity, including classloader identity,
  with cached accessors and applicable-rule lookup.
- Enrich the current span or create and end one fallback `INTERNAL` span.
- Keep all configuration, reflection, telemetry, and instrumentation failures
  isolated from application methods.
- Verify the behavior with focused unit and integration-style tests.
- Verify the packaged extension in a real javaagent smoke test using the
  OpenTelemetry extension example's Testcontainers/fake-backend pattern.

**Non-Goals:**

- Runtime configuration reload or configuration merging.
- Map traversal, transformations, filters, user-defined code, or additional
  instrumentation points.
- Adding XML, reflection, bytecode, or configuration-framework dependencies.

## Code Style

Implementation code MUST be clean, idiomatic Java following established best
practices. Methods MUST be small, focused, and single-purpose, with simple
control flow and low cyclomatic complexity. Method signatures MUST remain
small; related data SHOULD be grouped when that improves clarity.

Classes MUST have one clear responsibility. The implementation MUST avoid
unnecessary abstractions, indirection, inheritance, and design patterns; an
abstraction is justified only when it provides clear value for the agent API,
testing, or a classloader boundary. Code MUST favor readability and
maintainability over cleverness or speculative optimization and MUST be
readable and modifiable by a junior developer.

Comments, explanations, and supporting code MUST be concise and limited to
what is needed to make the implementation clear.

## Java 21 Quality Rules

Production code MUST satisfy SonarLint rules and MUST introduce no SpotBugs
warnings. New code MUST be checked with the project's configured static
analysis and quality tasks before completion.

The implementation MUST apply SOLID principles without adding speculative
abstractions. State and value objects MUST be immutable wherever practical.
Public APIs and collaborators MUST remain simple to unit test without an agent
runtime or container where the behavior does not require one.

The implementation MUST use `try`-with-resources for every owned
`AutoCloseable` resource. `Optional` MUST be used only for an actually
optional return or value boundary; it MUST NOT be used for fields, parameters,
collections, or flow-control in place of a clear branch.

Records and Java 21 pattern matching SHOULD be used where they improve the
model's clarity. Collection APIs SHOULD use modern intent-revealing methods
such as `getFirst()` where supported instead of positional access such as
`get(0)`.

Local variables and method parameters MUST be explicitly declared `final`.
The Java `var` keyword MUST NOT be used; explicit declared types are required.
String construction MUST use text blocks for multiline literals and
`String.format` or equivalent clear formatting for interpolation. Java/Spring
template interpolation syntax MUST NOT be introduced unless supplied by an
existing project dependency.

Code MUST avoid duplication, preserve straightforward control flow, and keep
all production code easy to inspect, debug, and unit test.

## Decisions

### Startup compiler and immutable state

Use JDK Base64, strict UTF-8 decoding, the JDK XML parser with secure feature
configuration, and small immutable Java model types. Parse and validate the
document, resolve root classes through the agent's application classloader
strategy, compile paths into property/index segments, and publish one complete
runtime state. On any error, publish disabled state and register nothing.

Alternative: retain XML or path strings for lazy runtime interpretation. This
was rejected because it adds hot-path parsing, mutability, and classloader
risk.

### Class-based rule lookup

Index rules by resolved root `Class<?>` and use a bounded concurrent runtime
class cache for assignable matches. The key is the actual `Class` object, so
classes with equal names from different loaders remain distinct.

Alternative: scan every rule by class name at each method exit. This was
rejected for predictable hot-path cost and incorrect classloader semantics.

The conceptual name-based grouping is therefore implemented as a `Class<?>`-
based index. This preserves fast grouped lookup while preserving defining
classloader identity.

Configuration loading occurs in the agent/configuration classloader. Application
classes to instrument are loaded by a separate application classloader. A
small agent-supported bridge MUST hand over immutable compiled configuration
and class-based lookup state between these loaders without eagerly loading
application model classes into the agent loader.

The handover MUST be loader-neutral. The snapshot MUST contain only immutable
JDK values: static rules, dynamic keys, root binary names, and compiled
property/index segments. It MUST NOT transfer an agent-loader-owned `Class<?>`
reference into application-loader runtime code. Each application loader MUST
resolve its own root `Class<?>` and build its own class-keyed rule index.

Runtime state MUST be isolated per application `ClassLoader` with weak or
equivalent lifecycle-safe keys. Initialization MUST be idempotent per loader,
and failure for one loader MUST NOT overwrite successful state for another.

### Resolver and accessor cache

Resolve `getX()`, then boolean `isX()`, then the exact field name. Cache both
successful and failed accessors by defining class and property name. Traverse
only the compiled finite segment list; flatten collection-like values in
iteration order and select indexed values without copying an entire iterable.

Alternative: use a general property-expression or reflection library. This
was rejected because dependencies are forbidden and the supported grammar is
small.

### Agent instrumentation boundary

Implement the repository's OpenTelemetry Java Agent extension using an
`InstrumentationModule` registered through the agent extension mechanism. The
module MUST expose `TypeInstrumentation` implementations, and each type
instrumentation MUST apply Byte Buddy Advice through the selected
`TypeTransformer` API. The method matcher and advice class MUST follow the
official extension-example pattern, adapted to configured application root
classes.

The instrumentation scope is determined by declarative configuration: only
instance methods of configured root classes and supported subclasses are
eligible. Constructors, static, abstract, native, bridge, and synthetic
methods MUST be excluded.

The `InstrumentationModule` MUST own module naming, classloader matching, and
the list of `TypeInstrumentation` instances. `TypeInstrumentation` MUST own
type matching and method transformation. Advice MUST remain small and
delegate to a runtime bridge that performs rule lookup, resolution, and
telemetry. The module MUST be discoverable through the project's supported
service-registration convention, such as
`@AutoService(InstrumentationModule.class)` when that is the selected API.

The module MUST expose every runtime class required by injected Advice through
the agent helper mechanism, including nested helper classes and transitive
bridge, model, parser, resolver, cache, telemetry, and Advice classes. The
Advice class MUST be resolvable in the application loader at execution time.

The Advice MUST lazily initialize state for the receiver's actual defining
loader. Repeated module matcher calls for multiple loaders MUST be safe.

Alternative: introduce a second agent bootstrap mechanism. This was rejected
because it would conflict with the existing agent lifecycle and classloader
support.

Alternative: implement a standalone `java.lang.instrument` transformer or raw
Byte Buddy registration outside `InstrumentationModule` and
`TypeInstrumentation`. This was rejected because it bypasses the supported
agent extension lifecycle.

### Telemetry failure isolation

At method exit, apply static and supported dynamic values to a valid current
span. If no valid current span exists, create one `INTERNAL` fallback span and
end it in `finally`. Wrap the whole enrichment boundary and each rule so one
failure cannot escape or suppress independent attributes.

The runtime MUST use a re-entrancy guard so nested instrumented getters,
serialization, telemetry, or logging cannot recursively enrich the same event.

Alternative: let telemetry exceptions propagate or create fallback spans
unconditionally. Both were rejected because application behavior and span
cardinality are explicit requirements.

If fallback tracer or span creation fails, the extension MUST swallow the
failure after best-effort cleanup and emit one warning. Repeated runtime
resolution failures remain silent at normal log level.

### Integration smoke-test harness

The integration tests MUST follow the official extension examples:
`IntegrationTest`, `SpringBootIntegrationTest`, and `OkHttpUtils`. The harness
MUST use a Testcontainers network with a fake OTLP backend and target
application, bounded HTTP clients, trace polling, and assertions over exported
`ExportTraceServiceRequest` data.

The harness MUST verify extension-jar loading, extension-directory loading,
and extension embedded in an extended javaagent jar when those modes are
available. The Gradle build MUST package a single Shadow extension jar and an
extended javaagent jar while preserving the upstream agent manifest. Agent,
extension, and extended-agent paths MUST be passed to tests through system
properties.

The target MUST receive the agent through `JAVA_TOOL_OPTIONS`, export OTLP data
to the fake backend, and use a bounded readiness wait. The smoke test MUST
assert the application response plus static, nested dynamic, and indexed
dynamic attributes. Docker-dependent tests MUST be safely skippable; unit
tests MUST remain runnable without Docker.

The integration fixture MUST expose public JavaBean getters for configured
properties, such as `getBrand()`, `getPassengers()`, and `getName()`. Record
component methods such as `brand()` and `name()` alone are not supported
property accessors.

## Risks / Trade-offs

- [Reflection access varies by module and visibility] -> Cache inaccessible
  accessors as negative results and treat invocation failures as null.
- [Classloader references can retain application loaders] -> Keep caches
  bounded to active instrumentation and do not store application objects.
- [Runtime caches can grow without control] -> Cap the applicable-rule cache
  at 1000 entries and evict the oldest entry using FIFO. The accessor cache is
  keyed by defining `Class<?>` via `ClassValue`, so its entries are released
  together with their defining classloader on collection; the bound is
  instrumented-class lifetime rather than a fixed cap, which removes the
  per-call `Key` allocation that previously dominated the accessor hot path.
- [Concurrent accessor discovery may race] -> `ConcurrentHashMap.computeIfAbsent`
  on the per-class map runs discovery at most once per `(class, property)`,
  so concurrent lookups never observe a partially resolved accessor.
- [Iterable indexing may be O(n)] -> Iterate only to the requested position;
  do not copy the collection. This is the specified trade-off for generic
  iterables.
- [Secure XML features differ across JDKs] -> Set supported hardening
  features, reject unsupported secure configurations, and test external entity
  input.
- [Byte Buddy matching can cause duplicate instrumentation] -> Centralize
  module registration and test that a method exit enriches once.
- [OpenTelemetry agent APIs evolve between versions] -> Pin the implementation
  to the selected project API version and use the matching official extension
  example as the reference.
- [Configuration and application classes use different loaders] -> Keep
  compiled state in the agent-supported bridge and resolve application classes
  and accessors only in the application loader.

## Migration Plan

1. Add the extension implementation and tests without changing existing agent
   behavior when the environment variable is absent.
2. Enable it by setting `OTEL_CUSTOM_AGENT_CONFIG` to Base64-encoded UTF-8 XML.
3. On invalid configuration, rely on the disabled-state path; the application
   remains running and existing instrumentation remains unaffected.
4. Roll back by removing the extension registration or unsetting the
   environment variable. No persisted data or schema migration is required.

## Reference Implementation Guidance

Before implementation, inspect:

- Project setup: `https://github.com/open-telemetry/opentelemetry-java-instrumentation/tree/main`
- Module example: `https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/examples/extension/src/main/java/com/example/javaagent/instrumentation/DemoServlet3InstrumentationModule.java`
- Type/Advice example: `https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/examples/extension/src/main/java/com/example/javaagent/instrumentation/DemoServlet3Instrumentation.java`

The implementation MUST resolve the concrete package root, build files, API
version, bootstrap bridge, logging API, and test framework from the project
before writing code. If the repository is still only a specification
repository, project setup is a prerequisite task rather than an invitation to
invent a different agent architecture.
> Status: superseded. The original Base64 environment configuration described
> here was replaced by file-only configuration.
