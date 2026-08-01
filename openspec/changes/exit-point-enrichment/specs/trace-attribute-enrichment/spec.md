## MODIFIED Requirements

### Requirement: XML configuration SHALL follow the exact contract

The root element MUST be `configuration`. `static` and `dynamic` sections are optional and an absent section MUST be empty. The `static` section MUST contain zero or more `attribute` elements only, with non-blank `key` and `value` attributes. The `dynamic` section MUST contain zero or more `enrich` elements only, each bundling attribute rules that apply at one exit point.

The documented configuration shape MUST be supported without Java instrumentation code:

```xml
<configuration>
    <static>
        <attribute key="domain" value="cars"/>
        <attribute key="team" value="black"/>
    </static>
    <dynamic>
        <enrich class="com.example.Garage" method="park">
            <attribute key="brand"       path="$arg0.brand"/>
            <attribute key="passengers"  path="$arg0.passengers.name"/>
            <attribute key="customerCity" path="$this.customer.city"/>
        </enrich>
        <enrich class="com.example.CarFactory" method="createCar">
            <attribute key="brand" path="$return.brand"/>
        </enrich>
    </dynamic>
</configuration>
```

Each `<enrich>` element MUST have non-blank `class` and `method` attributes. The `class` attribute MUST be a non-blank fully qualified Java class name. The `method` attribute MUST be a non-blank Java identifier. Each `<enrich>` element MUST contain zero or more `<attribute>` child elements, each with non-blank `key` and `path` attributes. Two `<enrich>` elements MUST NOT share the same `(class, method)` pair.

Static attribute keys MUST be unique within the `static` section. Dynamic attribute keys MUST be unique within their containing `<enrich>` block. Keys MUST be unique across both sections, MUST be non-blank, MUST be no longer than 255 characters, MUST be compared exactly and case-sensitively, and MUST be accepted consistently by the OpenTelemetry API. Attribute `path` values MUST start with `$this`, `$argN` (N a non-negative decimal integer not exceeding 127), or `$return`. Attribute values MUST not be trimmed except for blank-input validation.

Unknown elements, unknown XML attributes, and namespaces MUST be rejected. The parser MUST NOT accept the previous flat `<dynamic><attribute path="com.example.Car.brand"/></dynamic>` form.

#### Scenario: Optional sections are empty
- **WHEN** a valid document omits `static`, `dynamic`, or both sections
- **THEN** each omitted section MUST be represented as an empty rule list

#### Scenario: Unknown XML structure is rejected
- **WHEN** a document contains a namespace, unknown element, unknown XML attribute, an `attribute` child directly under `dynamic`, or an `enrich` child under `static`
- **THEN** startup MUST reject the configuration

#### Scenario: Duplicate and invalid keys are rejected
- **WHEN** keys are duplicated within or across sections, blank, longer than 255 characters, or invalid for OpenTelemetry
- **THEN** startup MUST reject the configuration

#### Scenario: Missing enrich attributes are rejected
- **WHEN** an `<enrich>` element is missing the `class` or `method` attribute, or has a blank `class` or `method`
- **THEN** startup MUST reject the configuration

#### Scenario: Duplicate exit points are rejected
- **WHEN** two `<enrich>` elements share the same `(class, method)` pair
- **THEN** startup MUST reject the configuration

#### Scenario: Documented XML shape is accepted
- **WHEN** the documented configuration shape contains loadable exit-point classes with existing methods
- **THEN** startup MUST accept it without requiring application-side instrumentation code

#### Scenario: Legacy flat dynamic form is rejected
- **WHEN** the document uses the previous flat `<dynamic><attribute path="com.example.Car.brand"/></dynamic>` form
- **THEN** startup MUST reject the configuration with a configuration error
- **AND** the error MUST indicate that exit-point `<enrich class method>` blocks are now required

### Requirement: Dynamic paths SHALL be compiled with deterministic grammar

The path grammar MUST be:

```text
exit-path   := root-source ('.' segment)*
root-source := '$this' | '$arg' nonnegative-integer | '$return'
segment    := identifier | identifier '[' nonnegative-integer ']'
identifier := Java identifier characters, excluding '.', '[', and ']'
```

The parser MUST recognize a `$`-prefixed root source (`$this`, `$argN`, or `$return`) at the start of every path. The parser MUST reject paths that omit the root-source prefix. After the root source, the remaining dotted components MUST be property segments using the existing `PropertySegment` and `IndexedPropertySegment` grammar.

The parser MUST try the longest `<enrich class>` declared class name in `Class.forName(className, false, applicationLoader)` and MUST verify the `<enrich method>` name has at least one declaring method on that class. The compiled `ExitPoint` MUST bind `(resolved Class<?>, methodName)` to its `List<ExitRule>` records.

The compiled model MUST contain `RootSource` (the sealed type: `THIS`, `Argument(int index)`, `ReturnValue`), `ExitPoint`, `ExitRule`, and the existing `PropertySegment` and `IndexedPropertySegment` records. The runtime MUST NOT retain or parse the original path string.

The supported examples MUST compile as follows:

- `<enrich class="com.example.Garage" method="park">` with path `$arg0.brand` -> root `Argument(0)`, segments `[PropertySegment("brand")]`.
- `<enrich class="com.example.Garage" method="park">` with path `$arg0.passengers.name` -> root `Argument(0)`, segments `[PropertySegment("passengers"), PropertySegment("name")]`.
- `<enrich class="com.example.Garage" method="park">` with path `$arg0.passengers[1].name` -> root `Argument(0)`, segments `[IndexedPropertySegment("passengers", 1), PropertySegment("name")]`.
- `<enrich class="com.example.CarFactory" method="createCar">` with path `$return.brand` -> root `ReturnValue`, segments `[PropertySegment("brand")]`.

Empty segments, negative indexes, malformed brackets, multiple indexes on one segment, and trailing separators MUST be rejected. The root class name MUST be a non-blank fully qualified Java class name. Indexes MUST be decimal, non-negative, and fit in a Java `int`.

#### Scenario: Longest loadable class prefix is selected
- **WHEN** `<enrich class="com.example.Garage" method="park">` is declared with path `$arg0.customers[0].city`
- **THEN** the root class MUST be `com.example.Garage`
- **AND** the compiled segments MUST be `customers[0]` and `city`
- **AND** the root source MUST be `Argument(0)`

#### Scenario: Invalid path syntax is rejected
- **WHEN** a path has an empty segment, negative/overflowing index, malformed/multiple brackets, trailing separator, missing root-source prefix, or unknown `$` token
- **THEN** startup MUST reject the configuration

#### Scenario: `$arg` index validation
- **WHEN** a path uses `$arg`, `$arg-1`, `$arg99999999999999`, or `$arg` followed by non-digit characters
- **THEN** startup MUST reject the configuration

### Requirement: Runtime rules SHALL be immutable and exit-point-indexed

Dynamic rules MUST be grouped into immutable `ExitPoint` records binding `(resolved root class, method name)` to an immutable list of `ExitRule` records. Each `ExitRule` MUST contain the attribute key, the typed `RootSource`, and the compiled immutable segment list. Static rules MUST be represented separately. `ExitPoint`, `ExitRule`, root sources, segments, lists, and indexes MUST be immutable or unmodifiable and safe to publish between threads.

The exit-point index MUST be built once at startup and keyed by `record ExitPointKey(Class<?> type, String methodName)`, including defining classloader identity. Configured root classes MUST match runtime subclasses using `isAssignableFrom` semantics (so subclass overrides also enrich). Runtime lookup MUST use a safely bounded cache populated only for classes loaded by the agent and MUST NOT scan all configured exit points on every method exit.

The index MUST use class identity rather than only a binary class-name string. This requirement intentionally supersedes a name-only lookup because equal binary names from different classloaders MUST remain isolated. A runtime-class cache MAY make the assignable lookup effectively constant-time for warmed classes, but it MUST remain bounded and thread-safe.

The exit-point index and accessor caches MUST each contain at most 1000 entries. Eviction MUST be FIFO, and eviction MUST NOT change the immutable compiled configuration or its startup-built exit-point index.

#### Scenario: Subclass matches configured exit point
- **WHEN** `<enrich class="com.example.Car" method="produce">` is configured and the receiver is `SportsCar extends Car` overriding `produce`
- **THEN** the Car-declared exit point MUST apply to the receiver
- **AND** the index MUST resolve via `Car.class.isAssignableFrom(SportsCar.class)`

#### Scenario: Equal names from different classloaders remain isolated
- **WHEN** two classloaders define classes with the same binary name and the same method name
- **THEN** an exit point for one `Class<?>` MUST NOT match an object from the other classloader

### Requirement: Instrumentation SHALL enrich exit points safely

Instrumentation MUST be implemented as an OpenTelemetry Java Agent `InstrumentationModule` exposing one or more `TypeInstrumentation` instances. The module MUST follow the extension-module pattern shown by `DemoServlet3InstrumentationModule.java`. Each `TypeInstrumentation` MUST follow the type-matcher and `TypeTransformer.applyAdviceToMethod` pattern shown by `DemoServlet3Instrumentation.java`, adapted to configured exit-point classes.

The type matcher MUST use `hasSuperType` semantics for subclass overrides: a class matches if any supertype's name appears in the configured exit-point class names (`RuntimeBridge.rootClassNames()`). The matcher MUST be lazily compiled and cached by root-class-names identity, so reload picks up new classes without restarting the JVM.

Because ByteBuddy installs method matchers at agent bootstrap — before `RuntimeBridge.initialize` publishes the configured method names via `classLoaderMatcher` — the method matcher MUST install advice on every non-static, non-abstract, non-native, non-bridge, non-synthetic instance method of matching types, split by return type:

- `returns(void.class)` → `VoidAdvice` (no `@Advice.Return`; `$return` rules resolve to `null`).
- `not(returns(void.class))` → `ValueAdvice` (with `@Advice.Return Object`).

ByteBuddy raises "Cannot assign void to class java.lang.Object" if `@Advice.Return Object` is declared on a `void` method; the split avoids that.

At runtime, `EnrichmentRuntime.enrich` MUST call `ExitPointIndex.find(receiver.getClass(), methodName)`. If the result is an empty list (the method is not a configured exit point), the advice MUST short-circuit without fetching a span, writing attributes, or creating a fallback — the cost is one bounded-cache lookup. Only when the index returns at least one `ExitRule` does the advice proceed to fetch the current span and write attributes.

Constructors, static, abstract, native, bridge, and synthetic methods MUST NOT receive advice. Advice MUST return immediately when the receiver is null or enrichment is disabled.

Advice MUST use `@Advice.This`, `@Advice.AllArguments`, `@Advice.Origin Method`, and (for non-void) `@Advice.Return Object`. It MUST obtain applicable immutable `ExitRule`s from the `ExitPointIndex` for `(receiver.getClass(), invokedMethodName)`, resolve each rule's root via the typed `RootSource` switch, walk the segments via `ValueResolver`, convert the values, and obtain the current OpenTelemetry span/context. A valid current span MUST receive static and resolved dynamic attributes without creating a fallback. With no valid current span, the extension MUST create exactly one configured-tracer `INTERNAL` span, apply attributes, and end it in `finally`.

Advice MUST use `suppress = Throwable.class` so that enrichment failures NEVER alter the application's return value or thrown exception.

#### Scenario: Current span is enriched
- **WHEN** a configured exit-point method exits with a valid current span
- **THEN** static attributes MUST be written to that span
- **AND** resolved dynamic attributes MUST be written to that span
- **AND** no fallback span MUST be created

#### Scenario: Missing current span creates one fallback
- **WHEN** a configured exit-point method exits without a valid current span
- **THEN** exactly one `INTERNAL` fallback span MUST be created
- **AND** it MUST be ended even when enrichment fails

#### Scenario: Unconfigured and infrastructure classes are excluded
- **WHEN** a method belongs to an unconfigured class or excluded runtime class
- **THEN** the type matcher MUST NOT match it
- **AND** no advice MUST be installed

#### Scenario: Non-exit-point sibling methods short-circuit at runtime
- **WHEN** a matching type has instance methods not named in any configured `<enrich>` block
- **THEN** advice MUST be installed on those methods (ByteBuddy installs at bootstrap, before config is available)
- **AND** `ExitPointIndex.find` MUST return an empty-list sentinel
- **AND** the advice MUST short-circuit without fetching a span or writing any attribute

#### Scenario: Official extension architecture is used
- **WHEN** the agent discovers the extension
- **THEN** it MUST discover an `InstrumentationModule`
- **AND** that module MUST provide `TypeInstrumentation` instances
- **AND** those type instrumentations MUST install method advice through the supported `TypeTransformer` API
- **AND** no standalone `java.lang.instrument` transformer or second agent bootstrap MUST be used

#### Scenario: Method scope follows declarative configuration at runtime
- **WHEN** a method is the declared exit point on a configured class
- **THEN** `ExitPointIndex.find` MUST return its rules and enrichment MUST proceed
- **AND** constructors, static, abstract, native, bridge, and synthetic methods MUST NOT receive enrichment advice
- **AND** non-exit-point sibling methods MUST short-circuit via the empty-list sentinel

### Requirement: Property resolution SHALL use getter-first traversal from a configurable root

For each `PropertySegment`, the resolver MUST try `getX()`, then `isX()` for boolean properties, then a field with the exact property name. Only instance properties are supported. Getter invocation MUST NOT invoke arbitrary methods. Access checks MUST be respected where possible; inaccessible members and getter exceptions MUST resolve to null.

Traversal MUST start at the root object selected by `RootSource` (`$this` = receiver, `$argN` = Nth parameter, `$return` = method return value) and follow only the finite compiled segment list. It MUST support nested ordinary objects, primitives, boxed primitives, strings, enums, arrays, `List`, `Collection`, and `Iterable`. Maps MUST NOT be traversed or treated as collection-like.

For a normal segment, an array, collection, or iterable current value MUST be treated as collection-like and the remaining segments MUST be resolved for each element. A scalar current value MUST resolve the named property on that object. An indexed segment MUST first read its named property and then select from an array, `List`, `Collection`, or `Iterable`.

#### Scenario: Getter has precedence over field
- **WHEN** an object has both `getBrand()` and a `brand` field
- **THEN** the getter value MUST be used

#### Scenario: Boolean getter is supported
- **WHEN** a boolean property has an `isEnabled()` getter and no usable `getEnabled()` getter
- **THEN** `isEnabled()` MUST be used

#### Scenario: Field is the final fallback
- **WHEN** no supported getter is usable and an exact instance field exists
- **THEN** the field value MUST be used subject to access checks

#### Scenario: Root source selects the traversal start
- **WHEN** the rule's RootSource is `Argument(0)` and the first parameter has a readable `brand` property
- **THEN** the resolver MUST start traversal from that parameter, not from the receiver
- **AND** `brand` MUST be read from the parameter object