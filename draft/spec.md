# OpenSpec v2: Declarative OpenTelemetry Trace Attribute Enrichment Agent

## 1. Purpose

Build a Java OpenTelemetry Java Agent extension that adds configured static and
runtime-derived attributes to spans when configured application objects are
used.

The developer-facing configuration remains Base64-encoded XML in the
environment variable `OTEL_CUSTOM_AGENT_CONFIG`. The XML path is a small
configuration DSL only. It is parsed once at startup and must not exist in the
runtime model afterward.

The implementation must use Java and OpenTelemetry APIs/dependencies already
provided by the agent build. Do not add an XML, reflection, bytecode, or
configuration framework dependency.

## 2. Scope

### In scope

- Static attributes applied to every enriched span.
- Dynamic attributes resolved from the instrumented receiver (`this`).
- Base64 decoding and XML parsing at startup.
- Immutable compiled rules and a startup-built rule index.
- Byte Buddy/OTel agent instrumentation for configured root classes.
- Getter-first, field-second property access.
- Properties, indexed properties, arrays, `List`, `Collection`, and `Iterable`.
- Nested objects and `instanceof`-style root-class matching.
- Existing current-span enrichment and fallback span creation.
- Classloader-safe runtime operation.
- Tests and a minimal executable verification path.

### Out of scope

- Map traversal.
- Static method or static field traversal.
- Constructor, field-write, or arbitrary method instrumentation.
- Runtime configuration reload.
- Multiple configuration sources or configuration merging.
- Attribute transformations, filtering expressions, conditionals, aggregation,
  or custom user code.
- Runtime path parsing, regex matching, or string-based property traversal.
- Normal runtime logging.

## 3. User Configuration Contract

The environment variable name is exactly `OTEL_CUSTOM_AGENT_CONFIG`.

Its value is standard Base64 containing UTF-8 XML. Whitespace around the
environment-variable value may be ignored. A blank value, invalid Base64, or
non-UTF-8 input is a configuration error. A missing variable disables the
extension quietly.

The accepted document has this shape:

```xml
<configuration>
    <static>
        <attribute key="domain" value="cars"/>
        <attribute key="team" value="black"/>
    </static>
    <dynamic>
        <attribute key="brand" path="com.example.Car.brand"/>
        <attribute key="passengers" path="com.example.Car.passengers.name"/>
        <attribute key="city" path="com.example.Garage.customers[0].city"/>
    </dynamic>
</configuration>
```

### XML rules

- The root element must be `configuration`.
- `static` and `dynamic` are optional; an absent section is empty.
- Each section may contain zero or more `attribute` elements only.
- Static attributes require non-blank `key` and `value`.
- Dynamic attributes require non-blank `key` and `path`.
- Unknown elements and unknown attributes are configuration errors. Namespaces
  are not supported; a document that uses a namespace is invalid.
- Keys are compared exactly and are case-sensitive.
- Keys must be unique across both static and dynamic attributes.
- A key must be a valid OpenTelemetry attribute key: non-blank and no longer
  than 255 characters. The implementation must reject keys it cannot pass to
  the OpenTelemetry API consistently.
- Attribute values are not trimmed except for validation of blank input.

### Path grammar

The path is a dot-separated sequence of segments:

```text
path       := segment ('.' segment)*
segment    := identifier | identifier '[' nonnegative-integer ']'
identifier := Java identifier characters, excluding '.', '[', and ']'
```

- To split the path, try the longest prefix ending at a dot as a class name in
  the configured application classloader. Use the longest successfully loaded
  prefix as `rootClassName`; every remaining dotted component is a property
  segment. If no prefix loads, or if the remaining property list is empty, the
  path is invalid. This makes the boundary deterministic and supports package
  names and nested-class binary names without a second path syntax.
- The root class name must be a non-blank fully qualified Java class name.
- A path must contain a root class and at least one property segment.
- An index is decimal, non-negative, and must fit in a Java `int`.
- Empty segments, negative indexes, malformed brackets, multiple indexes on one
  segment, and trailing separators are invalid.
- The parser must compile the path into `PathSegment` objects during startup.

Examples:

```text
com.example.Car.brand
  rootClassName = com.example.Car
  segments = [PropertySegment("brand")]

com.example.Car.passengers.name
  rootClassName = com.example.Car
  segments = [PropertySegment("passengers"), PropertySegment("name")]

com.example.Garage.customers[0].city
  rootClassName = com.example.Garage
  segments = [IndexedPropertySegment("customers", 0), PropertySegment("city")]
```

## 4. Startup Lifecycle

Startup occurs once, before application classes are instrumented or used.

1. Read `OTEL_CUSTOM_AGENT_CONFIG`.
2. Decode standard Base64.
3. Decode bytes as UTF-8 using a strict decoder.
4. Parse XML with the JDK standard XML parser.
5. Configure secure XML processing: disable external entity resolution, DTDs,
   external schemas, and external access where supported by the JDK. XML must
   not read local files, network resources, or execute entities.
6. Validate document structure, attributes, keys, duplicate keys, and paths.
7. Resolve configured root classes using the agent's application classloader
   strategy. Failure to resolve any configured root class is a configuration
   error; do not silently instrument nothing.
8. Build immutable static rules, dynamic rules, path segments, accessor/cache
   metadata, and the rule index.
9. Register instrumentation only for successfully resolved configured root
   classes.
10. Publish the completed immutable runtime state atomically.

Any startup error produces one concise error log, disables this extension, and
leaves the application running. A disabled extension must not register partial
instrumentation or attempt runtime enrichment.

When the environment variable is absent, the extension is disabled quietly at
normal log level. This is the no-configuration case, not an error.

## 5. Runtime Model

The implementation may choose concrete class names, but it must provide the
following responsibilities and data model.

```java
record DynamicAttributeRule(
    String key,
    String rootClassName,
    List<PathSegment> segments
) {}

sealed interface PathSegment permits PropertySegment, IndexedPropertySegment {}

record PropertySegment(String propertyName) implements PathSegment {}

record IndexedPropertySegment(String propertyName, int index)
    implements PathSegment {}
```

If records or sealed interfaces are incompatible with the selected supported
Java version, use final immutable classes with equivalent semantics.

Requirements:

- The runtime model contains no original path string.
- All lists and maps exposed by the runtime model are unmodifiable.
- Rule objects and segments are immutable and safely publishable between
  threads.
- Static rules are represented separately from dynamic rules.
- The index is built once at startup and never mutated.

## 6. Rule Index and Class Matching

The runtime must index dynamic rules by resolved configured root `Class<?>`,
not only by class-name strings:

```text
Map<Class<?>, List<DynamicAttributeRule>>
```

The key must include the defining classloader. Two classes with the same binary
name from different classloaders are different keys.

Instrumentation must be registered for configured root types and use
`isAssignableFrom` semantics. A rule configured for `Car` applies to an object
whose runtime type is `SportsCar extends Car`.

At method exit, resolve applicable rules for the receiver type without scanning
all configured rules. The implementation may use a cached runtime-class
lookup, populated safely and bounded by the classes actually loaded by the
agent. It must not use an unbounded per-invocation linear scan through every
rule.

The instrumentation matcher must exclude agent, JDK, OpenTelemetry, and other
runtime implementation classes unless they are explicitly configured. It must
also avoid instrumenting interfaces and synthetic infrastructure methods where
the selected instrumentation API makes that distinction available.

## 7. Instrumentation and Span Flow

Use the OpenTelemetry Java Agent extension instrumentation mechanism and the
repository's selected Byte Buddy integration. Do not invent a second agent
bootstrap mechanism.

The instrumentation advice runs on method exit for methods of configured
classes. It must:

1. Return immediately if the extension is disabled.
2. Return immediately if the receiver is null.
3. Obtain the applicable immutable rules for the receiver's runtime class.
4. Resolve dynamic values from the receiver.
5. Obtain the current OpenTelemetry span/context.
6. If a valid current span exists, set static and resolved dynamic attributes
   on that span and do not create a fallback span.
7. If no valid current span exists, create one `INTERNAL` span using the
   configured OpenTelemetry tracer, set attributes, and end it in `finally`.
8. Never let enrichment, reflection, or telemetry errors escape into the
   application method.

Static attributes are applied to every enrichment event. Dynamic attributes
   with a null/unresolvable value are skipped rather than passed as a null
   value, because OpenTelemetry span attributes cannot represent null.

The exact instrumented method matcher is an implementation detail, but it must
be documented in code and tested to prove that configured classes are covered,
unconfigured classes are not, and subclass behavior works. The advice must not
run twice for the same method exit due to duplicate instrumentation registration.

## 8. Value Resolution

### Access order

For each `PropertySegment`:

1. Try a JavaBean getter named `getX()`.
2. For boolean properties also try `isX()`.
3. If no usable getter exists, try a field with the exact property name.
4. Respect Java access checks where possible; if the field/getter cannot be
   accessed, resolution returns null.

Only instance properties are supported. Getter invocation must not invoke
arbitrary methods beyond the supported getter names.

### Traversal

- Start with the advice receiver.
- Each property segment reads one property from the current object.
- A normal segment on an array, collection, iterable, or scalar is handled as
  follows: if the current value is a collection-like value, traverse every
  element and resolve the remaining segments for each element; otherwise read
  the named property on the current object.
- An indexed segment first reads the named property, then selects the requested
  element from an array, `List`, `Collection`, or `Iterable`.
- For a generic `Collection` or `Iterable`, indexed access may iterate until the
  requested position; it must not copy the entire collection solely for access.
- A path ending after a collection property produces an array attribute of the
  collection element values.
- A path continuing after a collection property produces an array of the final
  resolved values, preserving iteration order.
- An indexed path produces one scalar value.
- Empty collections produce an empty typed array attribute.
- A collection containing null/unresolvable elements omits those elements from
  the resulting array.
- Maps are not traversed and are not treated as collection-like.
- Cycles are safe because traversal follows only the finite configured segment
  list; no object graph search is performed.

### Supported source values

Property results may be primitives, boxed primitives, `String`, enums, arrays,
`List`, `Collection`, `Iterable`, or nested ordinary objects. The resolver may
return a value only when it can map it to a supported OpenTelemetry attribute
type.

Unsupported values, including maps and arbitrary objects at the final segment,
resolve to null. They must not be converted using an implicit `toString()`.

### OpenTelemetry attribute types

Use the typed `Span.setAttribute` overload matching the value:

- `String`, enum name: `String`.
- `boolean`/`Boolean`: `Boolean`.
- integral numeric values: `Long`.
- floating-point numeric values: `Double`.
- arrays/collections: arrays of one supported scalar type.

Mixed-type collections are invalid at runtime for that attribute and are
skipped. Primitive arrays must be boxed or copied into the corresponding OTel
array type without exposing mutable application storage to the span API.

## 9. Reflection and Caching

Reflection discovery must happen at startup or first class preparation, never
as repeated uncached lookup during every enrichment event.

Cache getter/field accessors by defining class and property name. The cache
must distinguish classes from different classloaders and must not retain
application classloaders after the agent no longer needs them beyond the
normal lifetime of loaded instrumented classes.

Cached accessors must capture whether the getter or field is usable. A failed
lookup is cached as a negative result to prevent repeated failed reflection.

Invocation exceptions are caught and treated as null. The original application
exception or method return value must never be changed by enrichment.

## 10. Classloader and Agent Boundary

- Agent bootstrap/runtime classes must be available to instrumented application
  bytecode through the OpenTelemetry agent's supported classloader mechanism.
- Advice must delegate to a small runtime bridge rather than embedding complex
  resolver logic into every instrumented class.
- The bridge must not load application model classes eagerly from the agent
  classloader.
- Configured root classes and their accessors must be resolved in the correct
  application classloader.
- Tests must cover two classloaders containing classes with the same binary
  name; rules for one loader must not enrich objects from the other loader.
- No application class, classloader, or object may be stored in a global static
  collection except through the bounded caches required for active
  instrumentation.

## 11. Error Handling

Configuration errors disable the extension before instrumentation. They include
missing required attributes, malformed XML/path/Base64, duplicate keys,
invalid UTF-8, unsupported XML structure, invalid attribute keys, and unresolved
root classes.

Runtime failures are isolated per enrichment event:

- null property, null collection, missing nested object, inaccessible member,
  getter exception, reflection exception, unsupported value, and out-of-range
  index produce a skipped attribute;
- no runtime exception is propagated into the application;
- failure in one rule does not prevent other rules or static attributes from
  being applied;
- span creation failure is swallowed after best-effort cleanup;
- fallback spans are always ended in `finally`.

## 12. Logging

Logging must be minimal because this code runs in application process hot paths.

At startup, log one informational message containing enabled/disabled status,
number of static rules, number of dynamic rules, and number of instrumented
root classes. Do not log configuration values because they may contain
sensitive data.

On startup failure, log one error with the failure category and cause. Do not
log the Base64 payload or full XML.

At runtime, produce no normal logs. Optional debug logging may be enabled only
through the agent's existing debug setting and must be rate-limited or omitted
for repeated resolution failures.

## 13. Package Responsibilities

Use the following small package layout, adapting the root package name to the
project:

```text
agent/
  config/parser/       Base64, secure XML, validation, path compilation
  config/model/        Immutable parsed/compiled configuration types
  runtime/model/       Rules, segments, immutable indexes
  runtime/resolver/    Segment execution and value conversion
  runtime/accessor/    Getter/field accessor abstraction
  runtime/cache/       Accessor and runtime-class lookup caches
  instrumentation/     Module, type matcher, advice
  telemetry/           Span/tracer access and typed attribute writing
  bridge/              Minimal application-to-agent runtime bridge
```

No package may own both XML parsing and runtime traversal. Avoid interfaces
with one implementation unless required by Byte Buddy, testing, or a classloader
boundary.

## 14. Performance and Concurrency

- Parse and compile configuration once.
- Perform no path splitting, regex, substring, or DSL interpretation in the
  method-exit hot path.
- Use immutable published configuration and rule data.
- Use cached accessors and cached applicable-rule lookup.
- Do not synchronize enrichment on the span, receiver, or global lock.
- Do not allocate a fallback span when a valid current span exists.
- Allocate only the values needed for configured attributes; collection output
  may allocate the required OTel array.
- All shared caches must be thread-safe. Lock-free reads are preferred, but a
  short safe initialization path is acceptable outside the steady-state read.
- Preserve application behavior even when enrichment is slow or fails.

## 15. Tests

Use the project's standard Java test framework if one is selected; otherwise
use the smallest standard test setup supported by the build. Tests must cover:

### Configuration

- valid configuration with static and dynamic sections;
- missing configuration disables quietly;
- valid Base64 and UTF-8 decoding;
- malformed Base64, XML, UTF-8, and paths;
- unknown elements/attributes;
- missing required attributes;
- duplicate keys across and within sections;
- invalid keys and unresolved root classes;
- secure XML rejection/no external entity access;
- compiled model contains segments and no runtime path dependency.

### Resolver

- getter property;
- boolean `isX` getter;
- field fallback;
- nested property;
- primitive and boxed values;
- arrays, list, collection, iterable;
- collection flattening for a terminal and nested path;
- indexed array/list/collection/iterable;
- empty collection;
- null at every traversal position;
- getter exception and inaccessible member;
- out-of-bounds and negative-invalid index;
- maps unsupported;
- mixed collection values skipped;
- cycles do not recurse beyond configured path length;
- typed OpenTelemetry values.

### Instrumentation

- only configured types are matched;
- subclass/`instanceof` behavior;
- separate classloaders do not share rules;
- current span is enriched;
- fallback internal span is created and ended when no span exists;
- static and dynamic attributes are both written;
- one broken rule does not block another;
- application method exceptions and return values are unchanged;
- runtime failures do not escape;
- instrumentation is not applied twice.

### Runtime properties

- concurrent enrichment is safe;
- no repeated accessor discovery after cache warm-up;
- no normal runtime logs;
- startup failure registers no partial instrumentation.

## 16. Acceptance Criteria

The implementation is complete only when all of the following are true:

1. A developer can configure the exact XML shown in this document without
   writing Java instrumentation code.
2. Startup compiles every valid dynamic path into immutable segments and does
   not retain or interpret the path at runtime.
3. Static attributes appear on every enrichment span.
4. Dynamic attributes are resolved from the receiver using getter-first,
   field-second semantics.
5. Collection and indexed behavior matches Section 8, including ordering and
   null handling.
6. Rules match configured root classes and subclasses while respecting
   classloader identity.
7. Runtime lookup uses startup-built/cached indexes and cached accessors; no
   all-rules scan or repeated reflection lookup occurs per method exit.
8. A current span is enriched, and exactly one ended `INTERNAL` fallback span
   is used only when no current span exists.
9. No configuration or runtime error can break the application method.
10. Invalid startup configuration disables only this extension and leaves the
    application running.
11. XML external entities and external resources cannot be resolved.
12. All tests in Section 15 pass, including classloader and concurrency tests.
13. The build uses no dependency outside Java standard APIs and existing
    OpenTelemetry/agent dependencies.

## 17. Deliberate Future Boundaries

The following may be added later without changing the current XML contract,
but must not be implemented now: map/key segments, filters, transformations,
custom converters, reloadable configuration, additional instrumentation points,
and new segment types. New segment types must be added behind the existing
`PathSegment` model and must not reintroduce runtime string parsing.
