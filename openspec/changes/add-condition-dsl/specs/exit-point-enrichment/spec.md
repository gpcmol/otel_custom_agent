## MODIFIED Requirements

### Requirement: Dynamic rules SHALL be grouped into ExitPoint records

Each `<enrich class method>` block MUST compile to an immutable `ExitPoint` record binding `(rootClass, methodName)` to an immutable list of `ExitRule` records and an optional `Condition` (nullable). Each `ExitRule` MUST contain the attribute `key`, the typed `RootSource`, and the compiled immutable `List<PathSegment>` (reusing the existing `PropertySegment` / `IndexedPropertySegment` grammar). `ExitPoint` instances MUST be immutable and safely publishable between threads.

The optional `Condition` field MUST be `null` when the `<enrich>` block has no `expr` attribute, or when an `expr` attribute was present but failed parse-time validation (syntax, unresolved property, type mismatch, unknown function) — in both cases the block enriches unconditionally. The `Condition` MUST be a non-null compiled AST when `expr` is present and parses successfully; the runtime MUST evaluate the AST once per exit event and skip the whole block (static and dynamic attributes) when it evaluates to `false`.

The agent MUST reject duplicate `<enrich>` blocks for the same `(class, method)` pair at parse time. The team MUST consolidate all attributes for one exit point into a single block.

#### Scenario: Attribute rules are grouped by exit point
- **WHEN** a config declares `<enrich class="com.example.Garage" method="park">` with three `<attribute>` children and no `expr`
- **THEN** the compiled model MUST contain exactly one `ExitPoint` with `(com.example.Garage.class, "park")`
- **AND** that ExitPoint MUST bind an immutable list of three `ExitRule` records
- **AND** its `Condition` field MUST be `null`

#### Scenario: Exit point with a valid expression carries a compiled Condition
- **WHEN** a config declares `<enrich class="com.example.Garage" method="park" expr="$arg0.brand == 'BMW'">`
- **THEN** the compiled ExitPoint MUST carry a non-null `Condition` AST
- **AND** the runtime MUST evaluate the `Condition` once per `park` exit

#### Scenario: Exit point with a failed expression has a null Condition and enriches unconditionally
- **WHEN** a config declares `<enrich class="com.example.Garage" method="park" expr="$x ==">`
- **AND** the expression fails to parse
- **THEN** the parser MUST emit one startup warning naming `com.example.Garage#park`
- **AND** the compiled ExitPoint MUST have a `null` `Condition`
- **AND** the block MUST enrich unconditionally (the same as if `expr` were absent)

#### Scenario: Duplicate exit-point blocks are rejected
- **WHEN** two `<enrich>` blocks share the same `(class, method)` pair regardless of their `expr` values
- **THEN** startup MUST reject the configuration with a configuration error
- **AND** no partial instrumentation MUST be registered

### Requirement: Enrichment runtime SHALL write each attribute once per enrich event

The runtime MUST iterate the applicable `ExitRule` list once per enrich call, resolve each rule's root, walk the segments, convert the value, and write to the current span — without re-running for the same exit event. Static attributes MUST be written once per enrich event, not once per resolved dynamic attribute. The runtime MUST resolve the current span once per enrich event, not once per attribute.

Before any attribute write, the runtime MUST evaluate the `ExitPoint`'s optional `Condition` (if non-null) exactly once against `(receiver, arguments, returned)` and MUST skip the whole block — static and dynamic attributes, current-span lookup, fallback-span creation, and the reentrancy `ThreadLocal` — when the `Condition` evaluates to `false`. When the `Condition` is `null` (absent `expr` or disabled by a parse failure), the runtime MUST behave as today and write all configured attributes.

Static-cache rebuilding (`staticKeys`/`staticValues` arrays) MUST continue to use the existing identity-based rebuild pattern (rebuild only when `RuntimeState` identity changes). The cache rebuild MUST NOT occur when a `false` `Condition` short-circuits the event before static-diff work.

The runtime MUST NOT evaluate the `Condition` when `ExitPointIndex.find` returned the empty-list sentinel (non-exit-point sibling calls short-circuit first, paying zero expression cost).

#### Scenario: Static attributes are written once per enrich event
- **WHEN** an enrich event applies 5 static and 10 dynamic rules and the `Condition` is `null` or evaluates to `true`
- **THEN** each static attribute MUST be written exactly once to the current span
- **AND** each dynamic attribute MUST be written exactly once
- **AND** `Span.setAttribute` calls MUST total 15, not 150

#### Scenario: One current-span lookup per event
- **WHEN** an enrich event processes N rules
- **THEN** `Span.current()` MUST be queried exactly once
- **AND** all attribute writes MUST target the same span reference

#### Scenario: False condition skips the whole block
- **WHEN** the `ExitPoint`'s `Condition` is non-null and evaluates to `false`
- **THEN** no static attribute MUST be written
- **AND** no dynamic attribute MUST be written
- **AND** `Span.current()` MUST NOT be queried
- **AND** the fallback span MUST NOT be created
- **AND** the reentrancy `ThreadLocal` MUST NOT be set

#### Scenario: False condition does not allocate static cache
- **WHEN** a `Condition` evaluates to `false` and `RuntimeState` identity has changed since the last event
- **THEN** `buildStaticCache` MUST NOT be invoked for the skipped event
- **AND** the static-cache arrays MUST NOT be rebuilt

#### Scenario: Null condition retains today's write behavior
- **WHEN** the `ExitPoint`'s `Condition` is `null`
- **THEN** the runtime MUST write the static and dynamic attributes exactly as today
- **AND** MUST NOT evaluate any expression AST