## ADDED Requirements

### Requirement: The `<enrich>` element SHALL accept an optional `expr` attribute

The `<enrich class method>` element MAY carry an optional `expr` attribute whose value is a boolean expression in the V1 DSL grammar. When `expr` is absent, the block enriches unconditionally (the current behavior). When `expr` is present and parses successfully, the compiled expression gates the block: at method exit, the expression is evaluated exactly once against `(receiver, arguments, return value)` and the block's static and dynamic attributes are written if and only if the expression evaluates to `true`. When `expr` is present but fails to parse or fails the parse-time type-check, the expression is silently disabled for that block (the block enriches unconditionally) and the agent emits exactly one warning at startup naming the block and the failure category.

The `expr` attribute MUST NOT affect instrumentation, class matching, or the `ExitPointIndex` lookup — non-exit-point calls short-circuit at `ExitPointIndex.find().isEmpty()` and pay zero expression cost. Only configured exit points evaluate the expression.

The expression MUST be evaluated in `EnrichmentRuntime.write` after the `ExitPointIndex.find` short-circuit and before any static or dynamic attribute work. A `false` result MUST short-circuit the whole block (static and dynamic attributes skipped, fallback span not created, reentrancy `ThreadLocal` not set) without raising an error.

#### Scenario: Expression absent retains current behavior
- **WHEN** an `<enrich>` block has no `expr` attribute
- **THEN** the block MUST enrich unconditionally on every method exit
- **AND** the attribute writes MUST match today's counts (static + dynamic rules)

#### Scenario: Expression true enriches normally
- **WHEN** an `<enrich>` block has `expr="$arg0.brand == 'BMW'"`
- **AND** at exit `$arg0.brand` equals `"BMW"`
- **THEN** the block MUST write its static and dynamic attributes to the current span
- **AND** the writes MUST total the configured static + dynamic rule count

#### Scenario: Expression false skips the whole block
- **WHEN** an `<enrich>` block has `expr="$arg0.brand == 'BMW'"`
- **AND** at exit `$arg0.brand` equals `"Audi"`
- **THEN** the block MUST NOT write any static or dynamic attributes
- **AND** MUST NOT create a fallback span
- **AND** MUST NOT set the reentrancy `ThreadLocal`

#### Scenario: Expression false short-circuits before span lookup
- **WHEN** the expression evaluates to `false`
- **THEN** `Span.current()` MUST NOT be queried
- **AND** no `setAttribute` call MUST be made for that block

#### Scenario: Non-exit-point calls pay zero expression cost
- **WHEN** a non-exit-point sibling method of a configured class exits
- **THEN** `ExitPointIndex.find` MUST return the empty-list sentinel
- **AND** the expression MUST NOT be evaluated
- **AND** the advice MUST return immediately

#### Scenario: Parse failure silently disables the expression
- **WHEN** `expr="$arg0.brand == "` (syntax error)
- **OR** `expr="$arg0.unknownProp == 'x'"` (unresolved property)
- **OR** `expr="$arg0.brand == 3"` (type mismatch: `String` vs `Long`)
- **OR** `expr="unknownFunc($arg0.x)"` (unknown function)
- **THEN** the parser MUST silently disable the expression for that block
- **AND** the block MUST behave as if `expr` were absent (unconditional enrichment)
- **AND** the rest of the configuration MUST remain enabled
- **AND** the agent MUST emit exactly one warning at startup naming the `class#method` pair and a short failure category

#### Scenario: Only one startup warning per failed block
- **WHEN** an `<enrich>` block's `expr` fails to parse
- **THEN** the agent MUST log one warning at startup
- **AND** MUST NOT log any further messages about that block at runtime
- **AND** MUST NOT log on subsequent enrichment events of that block

### Requirement: The V1 DSL grammar SHALL support a fixed expression vocabulary

An expression MUST be parseable as one of these productions, with parenthesised nesting of arbitrary depth:

- Atoms: `$this`, `$argN` (N=0..127 decimal), `$return`, dot-chained property navigation using the existing `PathSegment` grammar (`prop`, `prop[idx]`), string literals `'...'` or `"..."`, numeric literals (integer or decimal, narrowest type that matches the comparison's other operand), boolean literals `true` / `false`, collection literals `[a, b, c]`, and the `null` literal.
- Operators, lowest to highest precedence: `||`, `&&`, `!`, comparison (`==`, `!=`, `<`, `>`, `<=`, `>=`), `in`, arithmetic (`+`, `-`, `*`, `/`, `%`), then atoms. Parentheses override precedence.
- Functions: `size`, `contains`, `like`, `ilike`, `icontains`. The function vocabulary is fixed; any other identifier followed by `(` is a parse error.

The parser MUST accept quoted string literals beginning with either `'` or `"`. The outer XML attribute delimiter may differ from the inner literal delimiter; the parser MUST NOT require entity encoding for the inner literal's quoting character.

#### Scenario: Simple equality
- **WHEN** `expr="$arg0.brand == 'BMW'"`
- **THEN** the parser MUST produce a typed `Eq(Property($arg0.brand), StringLiteral("BMW"))` AST

#### Scenario: Nested boolean with parentheses
- **WHEN** `expr="$x == 'a' && ($y == 'b' || $z == 'c') && !$w"`
- **THEN** the parser MUST honour the parentheses in the AST grouping
- **AND** the AST MUST evaluate left-to-right with `&&` short-circuit

#### Scenario: SQL wildcard pattern
- **WHEN** `expr="ilike($arg0.model, 'x%')"`
- **THEN** the parser MUST accept the `%` wildcard in the literal

#### Scenario: Collection membership via `in`
- **WHEN** `expr="$arg0.mileage in [0, 100000]"`
- **THEN** the parser MUST produce an `In(Property, List<Literal>)` AST
- **AND** the comparison MUST be evaluable as `long == long` per the runtime types

#### Scenario: Unknown function is a parse error
- **WHEN** `expr="unknownFunc($arg0.x)"`
- **THEN** the parser MUST reject the expression
- **AND** the expression MUST be silently disabled per the failure-handling rule
- **AND** the warning MUST name the failure category as "unknown function"

#### Scenario: Unknown operator or punctuation is a parse error
- **WHEN** `expr="$x <=> $y"` or `expr="$x @ $y"`
- **THEN** the parser MUST reject the expression
- **AND** the warning category MUST identify the parse position

### Requirement: The parser SHALL type-check each expression at startup against reflected getter return types

For each `Property` atom in the expression, the parser MUST resolve the path root (`$this` → declared class, `$argN` → Nth parameter type of the declared exit-point method, `$return` → declared return type) and reflect on each intermediate property's getter return type (following `ValueResolver`'s getter-first, boolean-`isX`, field-access order and flatten-on-collection semantics). The leaf's type MUST be derived from the final property's reflected return type; collection flattening (`List<Person>` through `.name` → `List<String>`) MUST follow `ValueResolver`'s flatten rules.

Each operator and function MUST be type-checked against its operand types at parse time. Numeric comparisons (`<`, `>`, `<=`, `>=`) require numeric operands; `==` and `!=` accept any single type that matches across both operands; `in` requires the right operand to be a collection literal whose element type matches the left operand's type; string functions (`like`, `ilike`, `icontains`, `contains` on a `String`) require `String` operands; `contains` on a `Collection<T>` requires the second operand to be type-`T`; `size` requires a `Collection` or array operand. A type mismatch MUST silently disable the expression for that block (the block enriches unconditionally) and emit one startup warning naming the block and a short failure category including the operand types.

A path whose intermediate getter returns a raw (non-generic) `Collection`, `Iterable`, or `Map` MUST fail the type-check with the category "erased collection type" and silently disable the expression for that block. Maps are unsupported (matches `ValueResolver`'s existing null-on-Map semantics).

The hot path MUST NOT perform `instanceof`, type guessing, or runtime coercion — all type decisions MUST be made at parse time and recorded in the AST.

#### Scenario: String equality passes type-check
- **WHEN** `expr="$arg0.brand == 'BMW'"` and `$arg0`'s `getBrand()` returns `String`
- **THEN** the parser MUST type-check successfully
- **AND** the AST MUST record the comparison as `String.equals`

#### Scenario: Numeric mismatch fails type-check
- **WHEN** `expr="$arg0.brand == 3"` and `brand` is `String`
- **THEN** the parser MUST silently disable the expression for that block
- **AND** the warning category MUST name "type mismatch" with `String` and `Long`/`int`

#### Scenario: Collection-flatten leaf is typed via flatten semantics
- **WHEN** `expr="contains($arg0.passengers.name, 'Piet')"` and `passengers` is `List<Person>` whose `getName()` returns `String`
- **THEN** the parser MUST type the leaf as `List<String>`
- **AND** the `contains` function MUST compile to `Collection.contains(String)`

#### Scenario: Raw collection fails type-check
- **WHEN** `expr="size($arg0.items) > 5"` and `getItems()` returns raw `List` (no type parameter)
- **THEN** the parser MUST fail the type-check with category "erased collection type"
- **AND** the expression MUST be silently disabled

#### Scenario: Map root fails
- **WHEN** a leaf path navigates into a `Map`
- **THEN** the parser MUST fail the type-check with category "map unsupported"
- **AND** the expression MUST be silently disabled

#### Scenario: Hot path performs no type guessing
- **WHEN** a typed expression is evaluated at method exit
- **THEN** the interpreter MUST NOT use `instanceof` to dispatch operator types
- **AND** MUST NOT call any coercion method
- **AND** MUST invoke only the typed operator recorded in the AST at parse time

### Requirement: `$return` on a `void` exit-point method SHALL resolve to `null` and yield `false`

The parser MUST accept `$return` as a root in any expression regardless of whether the declared exit-point method returns `void`. For a `void` method, the parser MUST type the `$return`-rooted leaf as `Object` (effectively untyped) and compile any comparison involving that leaf to evaluate against the runtime value `null`. Per the universal null-handling rule, comparisons involving a `null` operand MUST yield `false` (except `==`/`!=` against the `null` literal, which test the null reference directly).

The runtime MUST NOT raise, log, or skip the block for a `$return`-rooted expression on a `void` method — the expression simply evaluates to `false` if any operand is `null`, and the block is gated off. No special handling in the advice or runtime is required.

#### Scenario: `$return == null` on a void method returns true
- **WHEN** the exit-point method is `void`
- **AND** `expr="$return == null"`
- **THEN** the expression MUST evaluate to `true` at runtime
- **AND** the block MUST enrich normally

#### Scenario: `$return.foo` on a void method resolves to null
- **WHEN** the exit-point method is `void`
- **AND** `expr="$return.foo == 'x'"`
- **THEN** `$return` MUST resolve to `null`
- **AND** `$return.foo` MUST resolve to `null`
- **AND** the comparison MUST evaluate to `false`
- **AND** the block MUST be skipped without error

#### Scenario: `$return` on a non-void method uses the declared return type
- **WHEN** the exit-point method returns `Car`
- **AND** `expr="$return.brand == 'BMW'"`
- **THEN** the parser MUST type the leaf as `String` (from `Car.getBrand()`)
- **AND** the runtime MUST evaluate against the actual return value

### Requirement: The expression SHALL be compiled to a sealed typed AST evaluated by a small interpreter

The parser MUST produce an immutable AST implementing a sealed `Condition` interface. Internal nodes MUST be records (`And`, `Or`, `Not`, `Compare`, `In`, plus boolean `Call` variants for `size`/`contains`/`like`/`ilike`/`icontains`). Leaves MUST implement a sealed `Leaf` interface (`Literal`, `Property`). `Property` leaves MUST carry the `RootSource`, the compiled `List<PathSegment>` (reusing the existing segments), and the reflected leaf class.

The interpreter MUST evaluate `Property` leaves by delegating to the existing `ValueResolver.resolve(root, segments)` — the same code path used by `<attribute>` resolution — so the expression and the attribute paths share `AccessorCache` entries. The interpreter MUST NOT perform string parsing, regex matching, or reflective dispatch at runtime; all operators and functions MUST be direct Java operations (`==`/`!=` on primitives or `String.equals`, `>`/`<` on primitives, `Collection.contains`, `String.startsWith`/`endsWith`/`contains` for `like`, per-char case-fold for `ilike`, etc.).

The interpreter MUST short-circuit `&&` (right term not evaluated when left is `false`) and `||` (right term not evaluated when left is `true`). `!` MUST evaluate its single term. The interpreter MUST use one stack-local `EvalContext` carried by reference (no per-node allocation) wrapping `(receiver, arguments, returned)`.

#### Scenario: `&&` short-circuits
- **WHEN** `expr="$arg0.brand != null && $arg0.brand == 'BMW'"` and `$arg0.brand` is `null`
- **THEN** the right term MUST NOT be evaluated
- **AND** the expression MUST evaluate to `false`

#### Scenario: `||` short-circuits
- **WHEN** `expr="$arg0.brand == 'a' || $arg0.brand == 'b'"` and the first term is `true`
- **THEN** the right term MUST NOT be evaluated
- **AND** the expression MUST evaluate to `true`

#### Scenario: `Property` leaf reuses `ValueResolver`
- **WHEN** the expression references `$arg0.brand` and an `<attribute>` block also references `path="$arg0.brand"`
- **THEN** the `Property` leaf MUST resolve via `ValueResolver.resolve` with the same `List<PathSegment>`
- **AND** the `AccessorCache` MUST return the same lambdified `Accessor` for both paths

#### Scenario: No runtime string parsing
- **WHEN** the interpreter evaluates any AST node
- **THEN** the node's record fields MUST be sufficient to invoke the operator directly
- **AND** no `String.split`, `Pattern.compile`, or `Class.getMethod` MUST be invoked

#### Scenario: Per-node allocation is zero
- **WHEN** the interpreter evaluates an N-node expression at method exit
- **THEN** the interpreter MUST NOT allocate any object on the heap
- **AND** MUST pass through the single `EvalContext` by reference

### Requirement: `like` and `ilike` SHALL use SQL wildcards and zero allocation

`like(string, pattern)` MUST match `string` against `pattern` using SQL wildcards only — `%` matches any character sequence (including empty), `_` matches exactly one character. The implementation MUST translate the pattern at evaluation time into a sequence of `startsWith`, `endsWith`, `contains`, and per-character equality checks, allocating no `Pattern`, `Matcher`, or substring. `ilike(string, pattern)` MUST behave identically with per-character ASCII case-folding applied to both `string` and `pattern`.

The implementation MUST NOT support escape sequences in V1 (no `\%` or `\_`); a literal `%` or `_` in the comparison value cannot be expressed as a pattern character (V2).

#### Scenario: Prefix pattern
- **WHEN** `like("BMW-X5", "BMW%")`
- **THEN** the result MUST be `true`
- **AND** no `java.util.regex.Pattern` MUST be allocated

#### Scenario: Suffix pattern
- **WHEN** `ilike("Piet", "%IET")`
- **THEN** the result MUST be `true` (case-insensitive suffix match)

#### Scenario: Contains pattern
- **WHEN** `like("aPiEt某某", "%PiEt%")`
- **THEN** the result MUST be `true`

#### Scenario: Single-char wildcard
- **WHEN** `like("Piet", "Pi_t")`
- **THEN** the result MUST be `true`
- **AND** the `_` MUST match exactly one character (not zero)

#### Scenario: No-match returns false without allocation
- **WHEN** `like("audi", "BMW%")`
- **THEN** the result MUST be `false`
- **AND** no `java.util.regex` object MUST be allocated

#### Scenario: Empty pattern matches only empty string
- **WHEN** `like("", "")`
- **THEN** the result MUST be `true`
- **WHEN** `like("x", "")`
- **THEN** the result MUST be `false`

### Requirement: `contains` and `icontains` SHALL support `String` and `Collection<String>` operands

`contains(haystack, needle)` MUST return `true` if `haystack` is a `String` that contains `needle` as a substring, or a `Collection<T>` that contains an element `equals(needle)`. The parser MUST reject `contains` at type-check time if the first operand is neither `String` nor `Collection<T>` with a known element type, or if the second operand is not a literal whose type matches `T`. The parse-time failure MUST silently disable the expression and emit one warning.

`icontains(haystack, needle)` MUST behave like `contains` with case-insensitive matching. For `String` operands, `icontains` MUST behave like `String.equalsIgnoreCase` on the full string (not substring); for `Collection<String>` operands, `icontains` MUST return `true` if any element `equalsIgnoreCase(needle)`. `icontains` on a `String` compares the *whole* string (equivalent to `String.equalsIgnoreCase`), as a case-insensitive alternative to `==`. `icontains` on a `Collection<String>` returns `true` if any element `equalsIgnoreCase(needle)`. `icontains` MUST NOT do substring search; case-insensitive substring matching is `ilike(haystack, "%needle%")`.

#### Scenario: String contains
- **WHEN** `contains($arg0.brand, "BMW")` and `$arg0.brand` is `"the BMW M5"`
- **THEN** the result MUST be `true`

#### Scenario: Collection contains
- **WHEN** `contains($arg0.tags, "vip")` and `$arg0.tags` is `List<String>` containing `"vip"`
- **THEN** the result MUST be `true`

#### Scenario: Flattened collection contains via path
- **WHEN** `contains($arg0.passengers.name, "Piet")`
- **AND** `passengers` is `List<Person>` with `.name` flattening to `List<String>` containing `"Piet"`
- **THEN** the result MUST be `true`

#### Scenario: String icontains is whole-string case-insensitive
- **WHEN** `icontains($arg0.country, "NL")` and `$arg0.country` is `"nl"`
- **THEN** the result MUST be `true`
- **AND** this MUST be equivalent to `equalsIgnoreCase("nl", "NL")`

#### Scenario: Collection icontains is element-wise case-insensitive
- **WHEN** `icontains($arg0.tags, "Vip")` and `$arg0.tags` contains `"VIP"`
- **THEN** the result MUST be `true`

#### Scenario: icontains does not substring-match
- **WHEN** `icontains($arg0.brand, "Bmw")` and `$arg0.brand` is `"the BMW M5"`
- **THEN** the result MUST be `false`
- **AND** the user MUST use `ilike($arg0.brand, "%bmw%")` for case-insensitive substring matching

#### Scenario: Type mismatch on contains fails the type-check
- **WHEN** `contains($arg0.mileage, 100)` and `mileage` is `Long` (neither `String` nor `Collection`)
- **THEN** the parser MUST reject the expression at type-check
- **AND** silently disable the expression for that block
- **AND** emit one warning naming the block and the type mismatch

### Requirement: Null-handling SHALL be universal and predictable

For boolean operators that compare operands (`==`, `!=`, `<`, `>`, `<=`, `>=`, `in`, `contains`, `like`, `ilike`, `icontains`), if any required operand is `null` at runtime the boolean result MUST be `false`, with these exceptions:

- `Leaf == null` (literal `null` on the right) MUST return `true` iff `Leaf` resolves to `null`.
- `Leaf != null` MUST return `true` iff `Leaf` resolves to non-`null`.
- `null == null` MUST return `true`.
- `null != null` MUST return `false`.

`!null` MUST return `true` (a `null` operand is treated as `false` for `!`'s purposes). `size(null)` MUST return `0`. Boolean operators `&&` and `||` MUST short-circuit and use the operand-as-boolean-truthiness rule below:

- For `&&`: left operand must be `boolean`; `null` is treated as `false`.
- For `||`: left operand must be `boolean`; `null` is treated as `false`.

The interpreter MUST NOT raise `NullPointerException` or any other exception during evaluation. A runtime evaluation exception MUST be swallowed (matching `EnrichmentRuntime`'s existing application-safety guarantee) and the expression MUST evaluate to `false` (block skipped).

#### Scenario: Comparison with null operand is false
- **WHEN** `$arg0.brand == 'BMW'` and `$arg0.brand` is `null`
- **THEN** the result MUST be `false`
- **AND** no `NullPointerException` MUST be raised

#### Scenario: Null check via == null
- **WHEN** `$arg0.brand == null` and `$arg0.brand` is `null`
- **THEN** the result MUST be `true`

#### Scenario: `&&` treats null as false
- **WHEN** `$arg0.electric && $arg0.brand == 'BMW'` and `$arg0.electric` is `null`
- **THEN** the left term MUST evaluate to `false`
- **AND** the right term MUST NOT be evaluated

#### Scenario: `!` treats null as false
- **WHEN** `!$arg0.electric` and `$arg0.electric` is `null`
- **THEN** the result MUST be `true`

#### Scenario: `size(null)` returns 0
- **WHEN** `size($arg0.orders) > 0` and `$arg0.orders` is `null`
- **THEN** `size($arg0.orders)` MUST equal `0`
- **AND** the comparison MUST be `0 > 0` → `false`

#### Scenario: Evaluation exception yields false
- **WHEN** the interpreter encounters a runtime error during evaluation (e.g. an accessor throws)
- **THEN** the exception MUST be swallowed
- **AND** the expression MUST evaluate to `false`
- **AND** the block MUST be skipped
- **AND** the application MUST continue unchanged

### Requirement: The configuration parser SHALL integrate `expr` parsing into the existing pipeline

`ConfigurationParser.parseDynamic` MUST, for each `<enrich>` element, read the optional `expr` attribute. When present and non-blank, the parser MUST invoke the new expression compiler with the resolved exit-point class and method signature (already available — the parser resolves the class for `verifyMethodExists`). The compiler returns either a compiled `Condition` (success) or a `Condition` marker indicating "silently disabled" (any parse or type-check failure). The parser MUST attach either the compiled `Condition` or `null` (for absent `expr`) to the resulting `ExitPoint` record.

The `validateAttributes` call for `<enrich>` MUST add `"expr"` to the allowed set alongside `"class"` and `"method"`. The parser MUST NOT change the validation of `<attribute>` children or the `<static>` section. The parser MUST NOT propagate expression parse failures as `ConfigurationException` — those failures are caught per-block, logged once, and produce a `null` `Condition` for that block.

The existing `seenExitPoints` duplicate-`(class, method)` check MUST apply unchanged — adding `expr` does not change `<enrich>` identity.

#### Scenario: `expr` is an allowed attribute on `<enrich>`
- **WHEN** the parser encounters `<enrich class="…" method="…" expr="…">`
- **THEN** `validateAttributes` MUST accept `expr`
- **AND** MUST NOT raise "unknown XML attribute"

#### Scenario: Expression parse failure does not fail the whole config
- **WHEN** one `<enrich>` block has `expr="$x =="` (syntax error)
- **AND** another `<enrich>` block has valid `expr`
- **THEN** the parser MUST compile the valid expression
- **AND** the invalid block MUST have `null` `Condition` (unconditional)
- **AND** the overall configuration MUST be enabled
- **AND** exactly one startup warning MUST be emitted for the invalid block

#### Scenario: `expr` does not affect duplicate-`<enrich>` detection
- **WHEN** two `<enrich>` blocks share `(class, method)` regardless of their `expr` values
- **THEN** the parser MUST reject the configuration with a duplicate-exit-point error

#### Scenario: Exit-point class is reflected for type-check
- **WHEN** `expr="$arg0.brand == 'BMW'"` is parsed
- **THEN** the parser MUST have resolved the exit-point class already (via `Class.forName`)
- **AND** MUST pass the method's parameter types to the expression compiler
- **AND** the compiler MUST reflect `getBrand()` on the parameter type to type-check the leaf

### Requirement: The DSL SHALL be isolated in its own package group with explicit integration points

All DSL code — lexer, parser, type-checker, AST types, and the evaluator — MUST live under the `org.otel.agent.expr` package group (with `org.otel.agent.expr.node` and `org.otel.agent.expr.parser` sub-packages). The package group MUST be self-contained: it MUST NOT depend on XML parsing, span/telemetry, instrumentation, or ByteBuddy types. The only permitted dependencies on the rest of the codebase are:

- `org.otel.agent.config.parser.ConfigurationParser` calls `org.otel.agent.expr.parser.ExpressionParser.parse` (cold path, startup/reload only).
- `org.otel.agent.config.model.ExitPoint` holds a nullable `org.otel.agent.expr.Condition` reference — the only model type that crosses the package boundary.
- `org.otel.agent.runtime.EnrichmentRuntime.gate` calls `org.otel.agent.expr.Condition.eval` (hot path, the single runtime evaluation point).
- `org.otel.agent.expr.node.Property` delegates leaf resolution to the existing `org.otel.agent.runtime.resolver.ValueResolver` via the shared `org.otel.agent.runtime.accessor.AccessorCache` — this reuse is required so that an expression atom `$arg0.brand` and an `<attribute path="$arg0.brand"/>` rule hit the same lambdified `Accessor` cache entry.

The DSL package group MUST NOT be imported by `org.otel.agent.instrumentation`, `org.otel.agent.telemetry`, `org.otel.agent.bridge`, or `org.otel.agent.webserver`. The DSL is a config-compiled, runtime-evaluated feature; it has no business in instrumentation, telemetry, bridge, or webserver code.

#### Scenario: DSL code lives only under `org.otel.agent.expr`
- **WHEN** the implementation is complete
- **THEN** every DSL class (lexer, parser, type-checker, AST node, evaluator, `Condition`, `Leaf`, `EvalContext`, `ExpressionCompileException`) MUST be declared in a package starting with `org.otel.agent.expr`
- **AND** no DSL class MUST be declared in `org.otel.agent.config`, `org.otel.agent.runtime`, `org.otel.agent.telemetry`, `org.otel.agent.instrumentation`, `org.otel.agent.bridge`, or `org.otel.agent.webserver`

#### Scenario: DSL package depends only on permitted codebase types
- **WHEN** the DSL package group's imports are inspected
- **THEN** imports from the rest of the codebase MUST be limited to `org.otel.agent.config.model.RootSource`, `org.otel.agent.config.model.PathSegment` (and its permitted subtypes), `org.otel.agent.runtime.resolver.ValueResolver`, and `org.otel.agent.runtime.accessor.AccessorCache`
- **AND** MUST NOT import XML, span, telemetry, instrumentation, bridge, or webserver types

#### Scenario: Expression and attribute path share accessor cache entries
- **WHEN** an `<enrich>` block has `expr="$arg0.brand == 'BMW'"` and an `<attribute path="$arg0.brand"/>` child
- **THEN** the `Property` leaf in the expression and the `ExitRule`'s path MUST resolve through the same `AccessorCache` instance
- **AND** the cache MUST contain exactly one entry for `(Car.class, "brand")` after both are resolved

### Requirement: The runtime gate SHALL be a single named, documented method

`EnrichmentRuntime` MUST expose the DSL evaluation as one named method `gate(ExitPoint, Object receiver, Object[] arguments, Object returned)` returning `boolean`. The method MUST be the single runtime call site that evaluates a `Condition`; no other runtime code path MUST invoke `Condition.eval`. The method MUST return `true` when the `ExitPoint`'s `Condition` is `null` (absent or disabled) or when it evaluates to `true`, and `false` when it evaluates to `false` or when evaluation throws. The method MUST carry a Javadoc contract describing: (a) when the block is enriched vs skipped, (b) that this is the only runtime evaluation point, (c) that the parser runs only at startup/reload.

The method MUST be package-private (or otherwise accessible to the runtime unit test) so the gate is unit-testable in isolation from span/attribute machinery. `EnrichmentRuntime.enrich` MUST call `gate` exactly once per exit event, immediately after `ExitPointIndex.find` returns the `ExitPoint` and before the reentrancy `ThreadLocal` is set.

#### Scenario: `gate` is the single evaluation point
- **WHEN** the runtime evaluates a `Condition`
- **THEN** the call MUST go through `EnrichmentRuntime.gate`
- **AND** no other runtime method MUST call `Condition.eval` directly

#### Scenario: `gate` returns true for null condition
- **WHEN** `gate` is called with an `ExitPoint` whose `Condition` is `null`
- **THEN** the method MUST return `true` without allocating an `EvalContext`

#### Scenario: `gate` returns true for true condition
- **WHEN** `gate` is called with a `Condition` that evaluates to `true`
- **THEN** the method MUST return `true`

#### Scenario: `gate` returns false for false condition
- **WHEN** `gate` is called with a `Condition` that evaluates to `false`
- **THEN** the method MUST return `false`

#### Scenario: `gate` returns false on evaluation exception
- **WHEN** `Condition.eval` throws any runtime exception
- **THEN** `gate` MUST catch it, return `false`, and MUST NOT propagate the exception to `enrich`

#### Scenario: `gate` is called once per exit event
- **WHEN** an exit-point method exits and the block has a non-null `Condition`
- **THEN** `gate` MUST be called exactly once
- **AND** MUST be called before the reentrancy `ThreadLocal` is set
- **AND** MUST be called before `Span.current()` is queried

### Requirement: The DSL SHALL be developed test-first with comprehensive situational coverage

The DSL implementation MUST follow a test-first discipline: the test tree `src/test/java/org/otel/agent/expr/` MUST be written before the production implementation and MUST run red against empty stubs. Production code in `org.otel.agent.expr` MUST be added incrementally until the test suite is green. No production DSL file MUST be committed without a failing test that necessitates it.

The test suite MUST cover at minimum these categories, each in its own test class or cohesive set of test methods:

- **Lexer**: every token class, both string literal forms, numeric literal forms, every operator, every punctuation character, rejection of unknown punctuation.
- **Parser / AST shape**: every operator's AST node, precedence, parentheses overriding precedence, arbitrary nesting, collection literals, `null`/`true`/`false` literals, `$this`/`$argN`/`$return` atoms, dot+index paths.
- **Type-checker success**: String equality, numeric comparisons, `in` against typed collection literals, each function's accepted operand types, collection-flatten leaf typing, `$return` on non-void and on void.
- **Type-checker failure**: String vs numeric mismatch, unknown function, unknown property, raw `Collection` element type, Map root, wrong function arity, `in` with mismatched element type, `contains` with non-String/non-Collection first operand.
- **Evaluator**: every operator's runtime behaviour with matching operands; `&&` and `||` short-circuit (verify the right term is NOT evaluated when the left short-circuits); `!` including `!null`.
- **Null handling**: `$x == null` / `$x != null` direct reference test; any other comparison with null operand → false (no `NullPointerException`); `size(null)` → 0; `contains`/`like`/`ilike`/`icontains` with null → false; runtime exception inside a leaf → expression evaluates to false.
- **`like`/`ilike`**: every wildcard form (`%` prefix, `%` suffix, `%` both ends, `_` single char, mixed `%`/`_`, empty pattern, pattern longer than value, no-match); case-sensitivity of `like`; case-insensitivity of `ilike` with ASCII fold; documented Unicode limitation (German ß, Turkish I).
- **`contains`/`icontains`**: `String.contains` substring, `Collection.contains` element equality, flattened-collection `contains` via path navigation, `icontains` whole-string equality on `String`, `icontains` element-wise on `Collection<String>`, `icontains` does NOT substring-match (negative test).
- **`size`**: `Collection.size()`, array length, `null` → 0, used in comparison.
- **`$return` on void**: leaf resolves to null, comparison yields false, `$return == null` yields true, no exception.
- **Parse-failure categories**: each category throws `ExpressionCompileException` with block id and named category.
- **`ConfigurationParser` integration**: valid `expr` produces non-null `Condition`; invalid `expr` produces `null` `Condition` and block enriches unconditionally; duplicate `<enrich>` detection still fires; unknown `expr` attribute rejected when allowed set is not extended.
- **`EnrichmentRuntime` gate**: false condition skips the whole block (zero `setAttribute`, zero fallback span, `ThreadLocal` not set); true condition enriches normally; null condition retains today's behaviour; `$return`-on-void skips; subclass inherits the declared block's condition.

#### Scenario: Tests are written before production code
- **WHEN** the DSL is implemented
- **THEN** the test tree `src/test/java/org/otel/agent/expr/` MUST exist before any production file in `src/main/java/org/otel/agent/expr/`
- **AND** the test suite MUST run red against empty stubs before production code is added

#### Scenario: Every operator has runtime coverage
- **WHEN` the test suite is complete
- **THEN** every operator (`==`, `!=`, `<`, `>`, `<=`, `>=`, `in`, `&&`, `||`, `!`, `+`, `-`, `*`, `/`, `%`) MUST have at least one test asserting its runtime behaviour with matching operand types

#### Scenario: Every function has runtime coverage
- **WHEN` the test suite is complete
- **THEN** every function (`size`, `contains`, `like`, `ilike`, `icontains`) MUST have tests covering its documented behaviour including null operands and edge cases

#### Scenario: Short-circuit is verified
- **WHEN` `A && B` is evaluated and `A` is false
- **THEN** `B` MUST NOT be evaluated (verified by a leaf that records evaluation)
- **WHEN` `A || B` is evaluated and `A` is true
- **THEN** `B` MUST NOT be evaluated

#### Scenario: Every parse-failure category is covered
- **WHEN` the test suite is complete
- **THEN** each failure category (syntax error, unknown function, unknown operator/punctuation, unresolved property, type mismatch, raw collection, Map root, wrong function arity) MUST have a test asserting that `ExpressionCompileException` is thrown with the correct block id and category