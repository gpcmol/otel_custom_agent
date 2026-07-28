## ADDED Requirements

### Requirement: The extension SHALL use the existing agent platform

The implementation MUST be Java and MUST use only Java and OpenTelemetry
agent APIs/dependencies already provided by the build. It MUST NOT add an XML,
reflection, bytecode, or configuration-framework dependency. It MUST use the
OpenTelemetry Java Agent extension mechanism and the repository's selected
Byte Buddy integration, rather than adding a second agent bootstrap mechanism.
Project setup MUST follow the structure and conventions of the OpenTelemetry
Java Instrumentation repository at
`https://github.com/open-telemetry/opentelemetry-java-instrumentation/tree/main`.
The project MUST use Java 21, Gradle, the OTel extension-module layout, and a
package root beginning with `org.otel`.

The implementation MUST use only Java language features supported by the
selected project Java version. If records or sealed interfaces are not
supported, it MUST use final immutable classes with equivalent semantics.

The XML contract is intentionally unchanged by this implementation. The XML
shown below MUST remain the only developer-facing declarative configuration;
the compiled segment model is an internal representation and MUST NOT require
developers to write Java instrumentation code or a second configuration DSL.

#### Scenario: Existing dependencies are sufficient
- **WHEN** the extension is built
- **THEN** it MUST compile using Java standard APIs and existing agent and
  OpenTelemetry dependencies only

#### Scenario: Internal compilation does not change the XML contract
- **WHEN** a developer configures the documented XML
- **THEN** the extension MUST accept that same XML contract
- **AND** no developer-facing compiled-path syntax MUST be introduced

### Requirement: Startup configuration SHALL be decoded and validated once

The extension MUST read exactly `OTEL_CUSTOM_AGENT_CONFIG`. The value MUST be
standard Base64 containing UTF-8 XML; surrounding environment-value whitespace
MAY be ignored. A missing variable MUST disable the extension quietly. A blank
value, invalid Base64, or invalid UTF-8 MUST be a configuration error.

Startup MUST occur once before application classes are instrumented or used and
MUST perform, in order: environment lookup, Base64 decoding, strict UTF-8
decoding, secure XML parsing, structural validation, key/path validation, root
class resolution, immutable model/index/cache construction, instrumentation
registration, and atomic publication of completed runtime state. It MUST
register instrumentation only for successfully resolved configured root
classes; failure to resolve any configured root class MUST be a configuration
error rather than silently producing no instrumentation.

#### Scenario: Missing configuration disables quietly
- **WHEN** `OTEL_CUSTOM_AGENT_CONFIG` is absent
- **THEN** the extension MUST disable itself
- **AND** it MUST register no instrumentation
- **AND** it MUST produce no normal-level error

#### Scenario: Valid configuration is compiled at startup
- **WHEN** the variable contains valid Base64-encoded UTF-8 XML
- **THEN** startup MUST compile all static and dynamic rules before publishing
  runtime state
- **AND** the runtime MUST NOT retain the original XML configuration path

#### Scenario: Startup failure does not affect the application
- **WHEN** configuration decoding, parsing, validation, or root-class
  resolution fails
- **THEN** the extension MUST disable itself
- **AND** it MUST log one concise error with category and cause
- **AND** it MUST register no partial instrumentation
- **AND** the application MUST continue running

### Requirement: XML configuration SHALL follow the exact contract

The root element MUST be `configuration`. `static` and `dynamic` sections are
optional and an absent section MUST be empty. Each section MUST contain zero
or more `attribute` elements only. Unknown elements, unknown attributes, and
namespaces MUST be rejected.

The documented configuration shape MUST be supported without Java
instrumentation code:

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

Static attributes MUST have non-blank `key` and `value`. Dynamic attributes
MUST have non-blank `key` and `path`. Keys MUST be compared exactly and
case-sensitively, MUST be unique across both sections, MUST be non-blank and
no longer than 255 characters, and MUST be accepted consistently by the
OpenTelemetry API. Attribute values MUST not be trimmed except for blank-input
validation.

#### Scenario: Optional sections are empty
- **WHEN** a valid document omits `static`, `dynamic`, or both sections
- **THEN** each omitted section MUST be represented as an empty rule list

#### Scenario: Unknown XML structure is rejected
- **WHEN** a document contains a namespace, unknown element, unknown XML
  attribute, or non-`attribute` child in a section
- **THEN** startup MUST reject the configuration

#### Scenario: Duplicate and invalid keys are rejected
- **WHEN** keys are duplicated within or across sections, blank, longer than
  255 characters, or invalid for OpenTelemetry
- **THEN** startup MUST reject the configuration

#### Scenario: Documented XML shape is accepted
- **WHEN** the documented configuration shape contains loadable root classes
- **THEN** startup MUST accept it without requiring application-side
  instrumentation code

### Requirement: XML parsing SHALL be secure

The XML parser MUST disable external entity resolution, DTDs, external
schemas, and external access where supported by the JDK. XML MUST NOT read
local files, network resources, or execute entities.

#### Scenario: External entity cannot access a resource
- **WHEN** XML contains an external entity referring to a local or network
  resource
- **THEN** the resource MUST NOT be accessed
- **AND** startup MUST reject the document or leave the entity unresolved

### Requirement: Dynamic paths SHALL be compiled with deterministic grammar

The path grammar MUST be:

```text
path       := segment ('.' segment)*
segment    := identifier | identifier '[' nonnegative-integer ']'
identifier := Java identifier characters, excluding '.', '[', and ']'
```

The parser MUST try the longest prefix ending at a dot as a class name in the
configured application classloader and MUST use the longest successfully
loaded prefix as `rootClassName`. The remaining dotted components MUST be
property segments. If no prefix loads, or no property remains, the path MUST
be invalid. The root class name MUST be a non-blank fully qualified Java class
name. Indexes MUST be decimal, non-negative, and fit in a Java `int`.

Empty segments, negative indexes, malformed brackets, multiple indexes on one
segment, and trailing separators MUST be rejected. The compiled model MUST
contain `PropertySegment` and `IndexedPropertySegment` equivalents and MUST
not retain or parse the original path string at runtime.

The supported examples MUST compile as follows:

- `com.example.Car.brand` -> root `com.example.Car`,
  `PropertySegment("brand")`.
- `com.example.Car.passengers.name` -> root `com.example.Car`,
  `PropertySegment("passengers")`, `PropertySegment("name")`.
- `com.example.Garage.customers[0].city` -> root `com.example.Garage`,
  `IndexedPropertySegment("customers", 0)`, `PropertySegment("city")`.

#### Scenario: Longest loadable class prefix is selected
- **WHEN** `com.example.Garage.customers[0].city` is configured and
  `com.example.Garage` is the longest loadable prefix
- **THEN** the root class MUST be `com.example.Garage`
- **AND** the compiled segments MUST be `customers[0]` and `city`

#### Scenario: Invalid path syntax is rejected
- **WHEN** a path has an empty segment, negative/overflowing index,
  malformed/multiple brackets, trailing separator, no loadable root, or no
  property segment
- **THEN** startup MUST reject the configuration

### Requirement: Runtime rules SHALL be immutable and class-indexed

Dynamic rules MUST contain the attribute key, resolved root class name, and
compiled segments. Static rules MUST be represented separately. Rule objects,
segments, lists, maps, and indexes MUST be immutable or unmodifiable and safe
to publish between threads.

The dynamic rule index MUST be built once at startup and keyed by resolved
`Class<?>`, including defining classloader identity. Configured root classes
MUST match runtime subclasses using `isAssignableFrom` semantics. Runtime
lookup MUST use a safely bounded cache populated only for classes loaded by
the agent and MUST NOT scan all configured rules on every method exit.

The index MUST use class identity rather than only a binary class-name string.
This requirement intentionally supersedes a name-only lookup because equal
binary names from different classloaders MUST remain isolated. A runtime-class
cache MAY make the assignable lookup effectively constant-time for warmed
classes, but it MUST remain bounded and thread-safe.

The applicable-rule and accessor caches MUST each contain at most 1000
entries. Eviction MUST be FIFO, and eviction MUST NOT change the immutable
compiled configuration or its startup-built root-class index.

#### Scenario: Subclass matches configured root
- **WHEN** a rule targets `Car` and the receiver is `SportsCar extends Car`
- **THEN** the `Car` rule MUST apply to the receiver

#### Scenario: Equal names from different classloaders remain isolated
- **WHEN** two classloaders define classes with the same binary name
- **THEN** a rule for one `Class<?>` MUST NOT match an object from the other
  classloader

### Requirement: Instrumentation SHALL enrich method exits safely

Instrumentation MUST be implemented as an OpenTelemetry Java Agent
`InstrumentationModule` exposing one or more `TypeInstrumentation` instances.
The module MUST follow the extension-module pattern shown by
`DemoServlet3InstrumentationModule.java`, including the supported module
registration/discovery convention. Each `TypeInstrumentation` MUST follow the
type-matcher and `TypeTransformer.applyAdviceToMethod` pattern shown by
`DemoServlet3Instrumentation.java`, adapted to configured application root
classes and the selected agent API version.

Instrumentation MUST run on method exit for methods of configured root types.
The matcher MUST use subclass semantics, exclude agent, JDK, OpenTelemetry,
and other runtime implementation classes unless explicitly configured, and
avoid interfaces and synthetic infrastructure methods where supported. Advice
MUST NOT execute twice for one method exit.

The declarative configuration MUST determine eligible types: configured root
classes and supported subclasses only. Constructors, static, abstract, native,
bridge, and synthetic methods MUST NOT receive enrichment advice.

Advice MUST return immediately when disabled or when the receiver is null. It
MUST obtain applicable immutable rules, resolve dynamic values, and obtain the
current OpenTelemetry span/context. A valid current span MUST receive static
and resolved dynamic attributes without creating a fallback. With no valid
current span, the extension MUST create exactly one configured-tracer
`INTERNAL` span, apply attributes, and end it in `finally`.

#### Scenario: Current span is enriched
- **WHEN** an applicable instrumented method exits with a valid current span
- **THEN** static attributes MUST be written to that span
- **AND** resolved dynamic attributes MUST be written to that span
- **AND** no fallback span MUST be created

#### Scenario: Missing current span creates one fallback
- **WHEN** an applicable method exits without a valid current span
- **THEN** exactly one `INTERNAL` fallback span MUST be created
- **AND** it MUST be ended even when enrichment fails

#### Scenario: Unconfigured and infrastructure classes are excluded
- **WHEN** a method belongs to an unconfigured class or excluded runtime class
- **THEN** enrichment advice MUST NOT run

#### Scenario: Official extension architecture is used
- **WHEN** the agent discovers the extension
- **THEN** it MUST discover an `InstrumentationModule`
- **AND** that module MUST provide `TypeInstrumentation` instances
- **AND** those type instrumentations MUST install method advice through the
  supported `TypeTransformer` API
- **AND** no standalone `java.lang.instrument` transformer or second agent
  bootstrap MUST be used

#### Scenario: Method scope follows declarative configuration
- **WHEN** a method is an instance method of a configured root class or
  supported subclass
- **THEN** it MAY receive enrichment advice
- **AND** constructors, static, abstract, native, bridge, and synthetic methods
  MUST NOT receive enrichment advice

### Requirement: Property resolution SHALL use getter-first traversal

For each `PropertySegment`, the resolver MUST try `getX()`, then `isX()` for
boolean properties, then a field with the exact property name. Only instance
properties are supported. Getter invocation MUST NOT invoke arbitrary methods.
Access checks MUST be respected where possible; inaccessible members and
getter exceptions MUST resolve to null.

Traversal MUST start at the advice receiver and follow only the finite
compiled segment list. It MUST support nested ordinary objects, primitives,
boxed primitives, strings, enums, arrays, `List`, `Collection`, and `Iterable`.
Maps MUST NOT be traversed or treated as collection-like.

For a normal segment, an array, collection, or iterable current value MUST be
treated as collection-like and the remaining segments MUST be resolved for
each element. A scalar current value MUST resolve the named property on that
object. An indexed segment MUST first read its named property and then select
from an array, `List`, `Collection`, or `Iterable`.

#### Scenario: Getter has precedence over field
- **WHEN** an object has both `getBrand()` and a `brand` field
- **THEN** the getter value MUST be used

#### Scenario: Boolean getter is supported
- **WHEN** a boolean property has an `isEnabled()` getter and no usable
  `getEnabled()` getter
- **THEN** `isEnabled()` MUST be used

#### Scenario: Field is the final fallback
- **WHEN** no supported getter is usable and an exact instance field exists
- **THEN** the field value MUST be used subject to access checks

### Requirement: Collection and indexed traversal SHALL preserve semantics

A normal segment on a collection-like current value MUST traverse every
element for the remaining segments; otherwise it MUST read the named property
from the current object. An indexed segment MUST read its named property and
select an element from an array, `List`, `Collection`, or `Iterable`. Generic
collection/iterable indexing MAY iterate to the requested position but MUST NOT
copy the entire collection solely for access.

A path ending after a collection property MUST produce an array attribute of
element values. A path continuing after a collection property MUST produce an
array of final values in iteration order. An indexed path MUST produce one
scalar value. Null or unresolvable elements MUST be omitted. Empty collections
MUST produce an empty typed array. Cycles MUST be safe because no object-graph
search is performed beyond the finite path.

#### Scenario: Nested collection traversal preserves order
- **WHEN** an ordered collection contains objects with readable `name`
  properties and the path continues through `name`
- **THEN** the result MUST be an ordered typed array of final names
- **AND** null or unresolvable elements MUST be omitted

#### Scenario: Indexed iterable does not copy all elements
- **WHEN** an indexed segment selects an element from a generic `Iterable`
- **THEN** resolution MAY iterate until the requested index
- **AND** it MUST NOT copy the entire iterable solely for access

#### Scenario: Empty and out-of-range collections are safe
- **WHEN** a collection is empty or an index is out of range
- **THEN** the result MUST be an empty typed array or skipped scalar as
  appropriate
- **AND** no exception MUST escape into the application

### Requirement: Values SHALL map only to typed OpenTelemetry attributes

The implementation MUST use typed `Span.setAttribute` overloads. Strings and
enum names MUST map to `String`; booleans to `Boolean`; integral numbers to
`Long`; floating-point numbers to `Double`; and arrays/collections to arrays
of one supported scalar type. Mixed-type collections MUST be skipped.
Primitive arrays MUST be boxed or copied into corresponding OTel arrays and
MUST NOT expose mutable application storage.

`Character` MUST map to `String`. `Byte` and `Short` MUST map to `Long`.
`BigInteger` MUST map to `Long` only when its value fits exactly in the Java
`long` range. `BigDecimal` MUST map to a finite `Double` only when conversion
does not produce an infinite value. Values that cannot be converted safely
MUST be skipped.

Unsupported values, including maps and arbitrary objects at the final segment,
MUST resolve to null and MUST NOT be converted with implicit `toString()`.
Dynamic null/unresolvable values MUST be skipped because OTel attributes cannot
represent null.

#### Scenario: Primitive and boxed numbers use typed values
- **WHEN** a resolved value is integral, floating-point, boolean, string, or
  enum
- **THEN** the matching typed OpenTelemetry attribute overload MUST be used

#### Scenario: Mixed collection is skipped
- **WHEN** a collection contains supported scalar values of different types
- **THEN** that dynamic attribute MUST be skipped

#### Scenario: Unsupported final value is not stringified
- **WHEN** the final value is a map or arbitrary ordinary object
- **THEN** no attribute MUST be written
- **AND** the value MUST NOT be converted with `toString()`

#### Scenario: Extended Java scalar types use safe mappings
- **WHEN** a resolved value is a `Character`, `Byte`, `Short`, fitting
  `BigInteger`, or finite `BigDecimal`
- **THEN** it MUST map to `String`, `Long`, `Long`, `Long`, or `Double`
  respectively
- **AND** overflowing or non-finite conversions MUST be skipped

### Requirement: Reflection and classloader boundaries SHALL be safe

Reflection discovery MUST occur at startup or first class preparation, never
as repeated uncached lookup during each enrichment event. Getter/field
accessors MUST be cached by defining class and property name, including
classloader identity. Failed lookups MUST be cached negatively. Invocation
exceptions MUST be caught and treated as null.

Agent bootstrap/runtime classes MUST be available through the agent's supported
classloader mechanism. Advice MUST delegate to a small runtime bridge and MUST
NOT eagerly load application model classes from the agent classloader.
Global static state MUST NOT retain application classes, classloaders, or
objects except bounded caches required by active instrumentation. Those caches
MUST NOT retain application classloaders beyond the normal lifetime required by
active instrumented classes.

Configuration MUST be loaded and compiled in the agent/configuration
classloader. Runtime instrumentation MUST execute with the application
classloader. The bridge MUST hand over immutable compiled rules and lookup
state between these boundaries without eagerly loading application model
classes into the configuration/agent classloader.

The handover MUST use a loader-neutral immutable snapshot containing only JDK
values: static rules, dynamic keys, root binary class names, and compiled
property/index segments. It MUST NOT transfer an agent-loader-owned `Class<?>`
reference into application-loader runtime code. Each application loader MUST
resolve its own root `Class<?>` and build a separate class-keyed rule index.

Runtime state MUST be isolated per application `ClassLoader` with weak or
equivalent lifecycle-safe keys. Initialization MUST be idempotent per loader,
and failure for one loader MUST NOT overwrite successful state for another.

Accessor discovery MUST use public getters only and MUST never call
`setAccessible`. Field fallback, where supported by the runtime contract, MUST
use only normally accessible fields; private or otherwise inaccessible fields
MUST resolve to null.

The module MUST expose all classes required by injected Advice through the
agent helper mechanism, including nested helper classes and transitive bridge,
model, parser, resolver, accessor, cache, telemetry, and Advice classes. The
Advice class MUST be available in the application loader when transformed code
executes. Advice MUST lazily initialize state for the receiver's actual
defining loader.

#### Scenario: Failed accessor lookup is cached
- **WHEN** a property cannot be accessed and is resolved repeatedly
- **THEN** the negative result MUST be reused
- **AND** reflection discovery MUST NOT repeat on every event

#### Scenario: Application classes are resolved in their loader
- **WHEN** a configured root class or accessor belongs to an application
  classloader
- **THEN** resolution MUST use the correct application loader
- **AND** unrelated classloaders MUST remain isolated

#### Scenario: Configuration crosses the loader boundary safely
- **WHEN** configuration is compiled in the agent classloader and a configured
  application type is loaded in a separate application classloader
- **THEN** runtime instrumentation MUST resolve and instrument the application
  type through its application classloader
- **AND** the bridge MUST transfer compiled state without eagerly loading the
  application model into the agent classloader

#### Scenario: Inaccessible members are not forced open
- **WHEN** a getter is non-public or a field is private/inaccessible
- **THEN** the resolver MUST NOT call `setAccessible`
- **AND** the property MUST resolve to null

#### Scenario: Loader-neutral handover preserves class identity
- **WHEN** the same root binary name is loaded by two application classloaders
- **THEN** both loaders MUST receive the same immutable loader-neutral rules
- **AND** each loader MUST build an index using its own `Class<?>`
- **AND** rules MUST NOT cross between the loaders

#### Scenario: Missing helper classes fail safely
- **WHEN** Advice or a transitive helper is unavailable in the application
  loader
- **THEN** setup MUST fail safely before enrichment
- **AND** no enrichment exception MUST escape into the application

### Requirement: Runtime failures SHALL be isolated per enrichment event

Enrichment, reflection, telemetry, span creation, and accessor/cache errors
MUST never escape into the application method. A failure in one rule MUST NOT
prevent other rules or static attributes from being applied. The original
application exception and method return value MUST remain unchanged. Fallback
spans MUST always be ended in `finally`, and span-creation failures MUST be
swallowed after best-effort cleanup.

The runtime MUST prevent recursive enrichment caused by nested instrumented
getters, serialization, telemetry, or logging. One method-exit event MUST
produce at most one enrichment event for that receiver/event.

#### Scenario: One broken rule does not block other rules
- **WHEN** one dynamic rule fails and another rule plus static attributes are
  valid
- **THEN** the failed attribute MUST be skipped
- **AND** the other rule and static attributes MUST still be applied

#### Scenario: Application behavior is unchanged
- **WHEN** enrichment fails while an application method throws or returns
- **THEN** the original exception or return value MUST be preserved
- **AND** no enrichment exception MUST escape

#### Scenario: Nested instrumentation does not recurse
- **WHEN** dynamic resolution invokes an instrumented getter
- **THEN** the nested getter exit MUST NOT recursively enrich the same event
- **AND** configured attributes MUST be written at most once for that event

### Requirement: Startup and runtime logging SHALL be minimal

At startup the extension MUST log one informational message containing enabled
or disabled status and counts of static rules, dynamic rules, and instrumented
root classes. It MUST NOT log configuration values, Base64, or full XML. At
runtime it MUST produce no normal logs. Optional debug logging MAY use only the
agent's existing debug setting and MUST be rate-limited or omitted for repeated
resolution failures.

If fallback tracer or span creation fails, the extension MUST emit one warning
and swallow the failure after best-effort cleanup. This warning MUST NOT expose
the Base64 payload or full XML. Repeated ordinary runtime resolution failures
MUST NOT produce normal-level logs.

#### Scenario: Startup status omits sensitive configuration
- **WHEN** startup succeeds or disables the extension
- **THEN** the status log MUST contain only status and rule/root counts
- **AND** it MUST NOT contain the Base64 payload or XML configuration

#### Scenario: Runtime failures produce no normal logs
- **WHEN** repeated property or telemetry resolution failures occur
- **THEN** normal runtime logging MUST NOT be emitted

#### Scenario: Fallback span creation failure is warned and isolated
- **WHEN** fallback tracer or span creation fails
- **THEN** one warning MUST be emitted
- **AND** the failure MUST NOT escape into the application method

### Requirement: Performance and concurrency SHALL preserve application behavior

Configuration and path compilation MUST happen once. The method-exit hot path
MUST perform no path splitting, regex, substring, or DSL interpretation. It
MUST use cached accessors and applicable-rule lookup, allocate only values
needed for configured attributes, and avoid fallback span allocation when a
valid current span exists.

Enrichment MUST NOT synchronize on the span, receiver, or a global lock. All
shared caches MUST be thread-safe; lock-free reads are preferred and only a
short safe initialization path is permitted outside steady-state reads.

Accessor and applicable-rule caches MUST be bounded to 1000 entries each and
MUST evict entries in FIFO order.

#### Scenario: Concurrent enrichment is safe
- **WHEN** multiple threads enrich receivers using shared immutable rules and
  caches
- **THEN** results MUST remain correct and isolated
- **AND** no unsafe mutation or race MUST corrupt runtime state

#### Scenario: Valid current span avoids fallback allocation
- **WHEN** a valid current span exists
- **THEN** enrichment MUST NOT allocate or create a fallback span

### Requirement: The implementation SHALL keep package responsibilities separate

The implementation MUST use separate packages for configuration parsing and
model, runtime model, resolver, accessor, cache, instrumentation, telemetry,
and the minimal bridge. No package may own both XML parsing and runtime
traversal. Interfaces with one implementation MUST NOT be added unless
required by Byte Buddy, testing, or a classloader boundary.

The package responsibilities SHOULD map to this layout, adapting the root
package name to the project:

```text
agent/
  config/parser/       Base64, secure XML, validation, path compilation
  config/model/        Immutable parsed/compiled configuration types
  runtime/model/       Rules, segments, immutable indexes
  runtime/resolver/    Segment execution and value conversion
  runtime/accessor/    Getter/field accessor abstraction
  runtime/cache/       Accessor and runtime-class lookup caches
  instrumentation/     InstrumentationModule, TypeInstrumentation, Advice
  telemetry/           Span/tracer access and typed attribute writing
  bridge/              Minimal application-to-agent runtime bridge
```

The implementation MUST NOT add map traversal, static method/field traversal,
constructor/field-write/arbitrary-method instrumentation, runtime reload,
multiple configuration sources or merging, transformations, filtering,
conditionals, aggregation, custom user code, runtime path parsing, regex
matching, string-based property traversal, or normal runtime logging.

Future boundaries MUST remain explicit: map/key segments, filters,
transformations, custom converters, reloadable configuration, additional
instrumentation points, and new segment types MUST NOT be implemented in this
change. Any future segment type MUST be added behind the existing
`PathSegment` model and MUST NOT reintroduce runtime string parsing.

#### Scenario: Out-of-scope behavior is not introduced
- **WHEN** the extension is implemented
- **THEN** it MUST provide only the configured instance-property enrichment
  behavior defined by this specification
- **AND** out-of-scope traversal, reload, transformation, and instrumentation
  features MUST NOT be added

### Requirement: Implementation code SHALL remain readable and focused

The implementation MUST use clean, idiomatic Java and established best
practices. Methods MUST be small, focused, single-purpose, and use simple
control flow with low cyclomatic complexity. Method signatures MUST remain
small; related data MAY be grouped when that improves clarity.

Each class MUST have one clear responsibility. The implementation MUST avoid
unnecessary abstractions, indirection, inheritance, and design patterns. An
abstraction MUST be introduced only when it provides clear value for the agent
API, testing, or a classloader boundary. Code MUST favor readability and
maintainability over cleverness or speculative optimization and MUST be
understandable and modifiable by a junior developer.

Comments and supporting explanations MUST be concise. The implementation MUST
not add bloated explanations, excessive comments, or unnecessary code.

#### Scenario: Code remains easy to maintain
- **WHEN** a developer reviews or modifies an implementation class
- **THEN** its responsibility, control flow, and dependencies MUST be clear
- **AND** no abstraction or comment MUST exist only for speculative future use

### Requirement: Java 21 code SHALL pass quality gates

Production code MUST satisfy SonarLint rules and MUST produce no SpotBugs
warnings. The project MUST run the configured static-analysis and quality
tasks as part of verification.

The implementation MUST apply SOLID principles, remain immutable wherever
practical, avoid duplicated logic, and keep production collaborators simple to
unit test. Every owned `AutoCloseable` resource MUST use try-with-resources.

`Optional` MUST be used only at genuine optional value or return boundaries. It
MUST NOT be used for fields, parameters, collections, or flow-control in place
of a direct branch. Records and Java 21 pattern matching SHOULD be used where
they make the model clearer. Collection access SHOULD use intent-revealing
methods such as `getFirst()` instead of positional `get(0)` where supported.

Local variables and method parameters MUST be explicitly declared `final`. The
Java `var` keyword MUST NOT be used; explicit variable types are required.
Multiline strings MUST use text blocks, and interpolated strings MUST use
`String.format` or another clear existing project mechanism. Spring/template
interpolation syntax MUST NOT be introduced without an existing dependency.

#### Scenario: Static analysis is clean
- **WHEN** the configured build and quality checks run
- **THEN** SonarLint violations MUST not be introduced
- **AND** SpotBugs MUST report no warnings
- **AND** all owned resources MUST be visibly closed through try-with-resources

#### Scenario: Java style is explicit and testable
- **WHEN** production code is reviewed
- **THEN** local variables and method parameters MUST be final with explicit
  types
- **AND** `var` MUST NOT occur in production Java code
- **AND** optional values, records, pattern matching, and collection access
  MUST use the simplest clear Java 21 construct

### Requirement: The implementation SHALL provide complete verification

Tests MUST use the project's standard Java test framework, or the smallest
standard setup supported by the build. Tests MUST cover valid configuration,
missing configuration, Base64/UTF-8/XML/path errors, unknown structure,
required attributes, duplicate/invalid keys, unresolved root classes, secure
XML, compiled immutable model without runtime path dependency, getter and
field access, nested and collection traversal, arrays, lists, collections,
iterables, indexes, empty/null values, getter exceptions, inaccessible
members, unsupported maps/values, mixed collections, typed OTel values,
subclass matching, duplicate instrumentation, separate classloaders, current
and fallback spans, failure isolation, unchanged application behavior,
concurrency, cache warm-up/negative results, no normal runtime logs, and no
partial startup instrumentation.

Instrumentation tests MUST also verify module discovery, the
`InstrumentationModule`/`TypeInstrumentation` split, configured type matching,
method advice installation, and the absence of duplicate advice registration.

The project MUST provide a real javaagent integration smoke test following the
OpenTelemetry extension examples `IntegrationTest`,
`SpringBootIntegrationTest`, and `OkHttpUtils`. It MUST use a Testcontainers
network with a fake OTLP backend and target application, bounded trace polling,
and assertions over exported `ExportTraceServiceRequest` data.

The smoke test MUST verify extension-jar loading, extension-directory loading,
and extension embedded in an extended javaagent jar where available. It MUST
pass agent, extension, and extended-agent paths through Gradle test system
properties and exercise `org.otel.example.Car`, `Passenger`, and `Garage`.
Exported traces MUST contain at least one static, one nested dynamic, and one
indexed dynamic attribute, while the target response remains correct.

Docker-dependent smoke tests MUST be safely skipped or opt-in when Docker or
the target image is unavailable. Unit, parser, resolver, and converter tests
MUST remain runnable without Docker.

#### Scenario: Required behavior is executable
- **WHEN** the standard project verification command runs
- **THEN** tests MUST verify the configuration, resolver, instrumentation,
  telemetry, classloader, concurrency, cache, security, and failure-isolation
  requirements
- **AND** all tests MUST pass before the implementation is considered complete

The project MUST also provide one minimal executable verification path, such as
the smallest supported example application or command, demonstrating a
configured object method enriching a current or fallback span without adding a
separate framework.

The minimal example SHOULD use an `org.otel.example` model with a `Car` that
has a public `getBrand()` getter and a public `getPassengers()` getter, a
`Passenger` with a public `getName()` getter, and a `Garage` with a public
`getCustomers()` getter. It MUST exercise the documented `brand`,
`passengers.name`, and `customers[0].city` paths, with at least one static
attribute and one dynamic attribute observable on the resulting span.

The integration model MUST expose public JavaBean getters. Record component
methods such as `brand()` and `name()` alone are outside the resolver contract;
records MAY additionally provide `getBrand()`, `getPassengers()`, and
`getName()` methods.

#### Scenario: Minimal executable verification works
- **WHEN** the documented verification command runs with a valid sample
  configuration
- **THEN** it MUST demonstrate at least one static and one dynamic attribute
  enrichment
- **AND** it MUST use the supported agent extension setup

#### Scenario: Runtime handover enriches the application span
- **WHEN** configuration is parsed in the agent context and the configured
  application class is loaded by a separate application classloader
- **THEN** the receiver loader MUST lazily obtain the immutable rule snapshot
- **AND** its class-keyed index MUST resolve the receiver's own `Class<?>`
- **AND** static and dynamic attributes MUST appear on the exported span

#### Scenario: Packaged extension enriches a real application
- **WHEN** the smoke test starts the target application with the extension jar
  or extended javaagent and invokes the configured model method
- **THEN** the target response MUST remain correct
- **AND** exported traces MUST contain the configured static attribute
- **AND** exported traces MUST contain the nested dynamic attribute
- **AND** exported traces MUST contain the indexed dynamic attribute

#### Scenario: Extension loading modes are equivalent
- **WHEN** the same smoke test runs with extension-jar, extension-directory,
  and embedded-extension loading
- **THEN** each available mode MUST produce equivalent enrichment assertions

#### Scenario: Unit verification does not require Docker
- **WHEN** Docker or the smoke-test image is unavailable
- **THEN** unit, parser, resolver, and converter tests MUST still run
- **AND** only Docker-dependent smoke tests MAY be skipped

### Requirement: Acceptance criteria SHALL be satisfied

The implementation is complete only when developers can configure the XML
contract without writing Java instrumentation code; every valid path is
compiled into immutable segments; static and supported dynamic attributes are
written with the specified collection/index semantics; root rules match
subclasses with classloader identity; cached indexes/accessors avoid repeated
reflection and all-rule scans; current or exactly one ended fallback span is
used correctly; errors cannot break the application; secure XML prevents
external access; all required tests pass; and no unapproved dependency is
added.

#### Scenario: End-to-end enrichment meets the contract
- **WHEN** a developer supplies the documented valid XML and invokes a
  configured application object
- **THEN** the extension MUST enrich the current span or one ended `INTERNAL`
  fallback span with the required static and dynamic typed attributes
- **AND** all startup, runtime, classloader, security, and application-safety
  requirements MUST remain satisfied
