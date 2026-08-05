## MODIFIED Requirements

### Requirement: The V1 DSL grammar SHALL support a fixed expression vocabulary

An expression MUST be parseable as one of these productions, with parenthesised nesting of arbitrary depth:

- Atoms: `$this`, `$argN` (N=0..127 decimal), `$return`, dot-chained property navigation using the existing `PathSegment` grammar (`prop`, `prop[idx]`), string literals `'...'` or `"..."`, numeric literals (integer or decimal, narrowest type that matches the comparison's other operand), boolean literals `true` / `false`, collection literals `[a, b, c]`, and the `null` literal.
- Operators, lowest to highest precedence: `or`, `and`, `not`, comparison (`==`, `!=`, `lt`, `lte`, `gt`, `gte`), `in`, arithmetic (`+`, `-`, `*`, `/`, `%`), then atoms. Parentheses override precedence.
- Functions: `size`, `contains`, `like`, `ilike`, `icontains`. The function vocabulary is fixed; any other identifier followed by `(` is a parse error.

`and`, `or`, and `not` MUST be treated as keywords, not functions. `not` MUST be followed by an expression — a function call, a comparison, or a parenthesised expression (`not <expression>`); the function-call form `not(...)` MUST be a parse error.

The symbolic forms `&&`, `||`, `!`, `<`, `>`, `<=`, `>=` MUST NOT be part of the grammar. An expression containing any of them MUST fail to parse (unknown operator or punctuation) and fall into the per-block fail-safe handling: the expression is silently disabled and the agent emits exactly one startup warning.

Because every operator and keyword is composed of letters, digits, `_`, `$`, `.`, `[`, `]`, `(`, `)`, quotes, `,`, `+`, `-`, `*`, `/`, `%`, and `=`, an expression can be placed verbatim into an XML attribute value without entity escaping.

#### Scenario: Simple equality
- **WHEN** `expr="$arg0.brand == 'BMW'"`
- **THEN** the parser MUST produce a typed `Eq(Property($arg0.brand), StringLiteral("BMW"))` AST

#### Scenario: Nested boolean with parentheses
- **WHEN** `expr="$x == 'a' and ($y == 'b' or $z == 'c') and not $w"`
- **THEN** the parser MUST honour the parentheses in the AST grouping
- **AND** the AST MUST evaluate left-to-right with `and` short-circuit

#### Scenario: Word operators in XML attribute without escaping
- **WHEN** `expr="$arg0.brand == 'BMW' and $arg0.year gte 2020 and not ilike($arg0.model, 'x%')"`
- **THEN** the parser MUST accept the expression exactly as written, with no entity references
- **AND** every operator (`and`, `gte`, `not`) MUST be recognized as a keyword, not as a property name

#### Scenario: `not` followed by a comparison
- **WHEN** `expr="not ($arg0.brand == 'BMW')"`
- **THEN** the parser MUST produce a `Not(Compare(...))` AST

#### Scenario: `not` followed by a function call
- **WHEN** `expr="not ilike($arg0.model, 'x%')"`
- **THEN** the parser MUST produce a `Not(Call(ilike))` AST

#### Scenario: `not(...)` function-call form is rejected
- **WHEN** `expr="not($arg0.model, 'x%')"`
- **THEN** the parser MUST reject the expression
- **AND** the expression MUST be silently disabled per the failure-handling rule
- **AND** the warning MUST identify the parse position

#### Scenario: Symbolic operators are parse errors
- **WHEN** `expr="$x == 'a' && $y == 'b'"` or `expr="$arg0.year < 2020"` or `expr="!$w"`
- **THEN** the parser MUST reject the expression (unknown operator)
- **AND** the expression MUST be silently disabled per the failure-handling rule

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

Each operator and function MUST be type-checked against its operand types at parse time. Numeric comparisons (`lt`, `gt`, `lte`, `gte`) require numeric operands; `==` and `!=` accept any single type that matches across both operands; `in` requires the right operand to be a collection literal whose element type matches the left operand's type; string functions (`like`, `ilike`, `icontains`, `contains` on a `String`) require `String` operands; `contains` on a `Collection<T>` requires the second operand to be type-`T`; `size` requires a `Collection` or array operand. A type mismatch MUST silently disable the expression for that block (the block enriches unconditionally) and emit one startup warning naming the block and a short failure category including the operand types.

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

#### Scenario: Word comparison operators require numeric operands
- **WHEN** `expr="$arg0.year gte 2020"` and `year` is `Long`
- **THEN** the parser MUST type-check successfully
- **AND** the AST MUST record the comparison as `long >= long`
- **WHEN** `expr="$arg0.brand gte 'BMW'"` and `brand` is `String`
- **THEN** the parser MUST fail the type-check with category "type mismatch"

#### Scenario: Collection-flatten leaf is typed via flatten semantics
- **WHEN** `expr="contains($arg0.passengers.name, 'Piet')"` and `passengers` is `List<Person>` whose `getName()` returns `String`
- **THEN** the parser MUST type the leaf as `List<String>`
- **AND** the `contains` function MUST compile to `Collection.contains(String)`

#### Scenario: Raw collection fails type-check
- **WHEN** `expr="size($arg0.items) gt 5"` and `getItems()` returns raw `List` (no type parameter)
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

### Requirement: The expression SHALL be compiled to a sealed typed AST evaluated by a small interpreter

The parser MUST produce an immutable AST implementing a sealed `Condition` interface. Internal nodes MUST be records (`And`, `Or`, `Not`, `Compare`, `In`, plus boolean `Call` variants for `size`/`contains`/`like`/`ilike`/`icontains`). Leaves MUST implement a sealed `Leaf` interface (`Literal`, `Property`). `Property` leaves MUST carry the `RootSource`, the compiled `List<PathSegment>` (reusing the existing segments), and the reflected leaf class.

The interpreter MUST evaluate `Property` leaves by delegating to the existing `ValueResolver.resolve(root, segments)` — the same code path used by `<attribute>` resolution — so the expression and the attribute paths share `AccessorCache` entries. The interpreter MUST NOT perform string parsing, regex matching, or reflective dispatch at runtime; all operators and functions MUST be direct Java operations (`==`/`!=` on primitives or `String.equals`, `gt`/`lt` on primitives, `Collection.contains`, `String.startsWith`/`endsWith`/`contains` for `like`, per-char case-fold for `ilike`, etc.).

The interpreter MUST short-circuit `and` (right term not evaluated when left is `false`) and `or` (right term not evaluated when left is `true`). `not` MUST evaluate its single term. The interpreter MUST use one stack-local `EvalContext` carried by reference (no per-node allocation) wrapping `(receiver, arguments, returned)`.

#### Scenario: `and` short-circuits
- **WHEN** `expr="$arg0.brand != null and $arg0.brand == 'BMW'"` and `$arg0.brand` is `null`
- **THEN** the right term MUST NOT be evaluated
- **AND** the expression MUST evaluate to `false`

#### Scenario: `or` short-circuits
- **WHEN** `expr="$arg0.brand == 'a' or $arg0.brand == 'b'"` and the first term is `true`
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

### Requirement: Null-handling SHALL be universal and predictable

For boolean operators that compare operands (`==`, `!=`, `lt`, `gt`, `lte`, `gte`, `in`, `contains`, `like`, `ilike`, `icontains`), if any required operand is `null` at runtime the boolean result MUST be `false`, with these exceptions:

- `Leaf == null` (literal `null` on the right) MUST return `true` iff `Leaf` resolves to `null`.
- `Leaf != null` MUST return `true` iff `Leaf` resolves to non-`null`.
- `null == null` MUST return `true`.
- `null != null` MUST return `false`.

`not null` MUST return `true` (a `null` operand is treated as `false` for `not`'s purposes). `size(null)` MUST return `0`. Boolean operators `and` and `or` MUST short-circuit and use the operand-as-boolean-truthiness rule below:

- For `and`: left operand must be `boolean`; `null` is treated as `false`.
- For `or`: left operand must be `boolean`; `null` is treated as `false`.

The interpreter MUST NOT raise `NullPointerException` or any other exception during evaluation. A runtime evaluation exception MUST be swallowed (matching `EnrichmentRuntime`'s existing application-safety guarantee) and the expression MUST evaluate to `false` (block skipped).

#### Scenario: Comparison with null operand is false
- **WHEN** `$arg0.brand == 'BMW'` and `$arg0.brand` is `null`
- **THEN** the result MUST be `false`
- **AND** no `NullPointerException` MUST be raised

#### Scenario: Null check via == null
- **WHEN** `$arg0.brand == null` and `$arg0.brand` is `null`
- **THEN** the result MUST be `true`

#### Scenario: `and` treats null as false
- **WHEN** `$arg0.electric and $arg0.brand == 'BMW'` and `$arg0.electric` is `null`
- **THEN** the left term MUST evaluate to `false`
- **AND** the right term MUST NOT be evaluated

#### Scenario: `not` treats null as false
- **WHEN** `not $arg0.electric` and `$arg0.electric` is `null`
- **THEN** the result MUST be `true`

#### Scenario: `size(null)` returns 0
- **WHEN** `size($arg0.orders) gt 0` and `$arg0.orders` is `null`
- **THEN** `size($arg0.orders)` MUST equal `0`
- **AND** the comparison MUST be `0 > 0` → `false`

#### Scenario: Evaluation exception yields false
- **WHEN** the interpreter encounters a runtime error during evaluation (e.g. an accessor throws)
- **THEN** the exception MUST be swallowed
- **AND** the expression MUST evaluate to `false`
- **AND** the block MUST be skipped
- **AND** the application MUST continue unchanged

### Requirement: The DSL SHALL be developed test-first with comprehensive situational coverage

The DSL implementation MUST follow a test-first discipline: the test tree `src/test/java/org/otel/agent/expr/` MUST be written before the production implementation and MUST run red against empty stubs. Production code in `org.otel.agent.expr` MUST be added incrementally until the test suite is green. No production DSL file MUST be committed without a failing test that necessitates it.

The test suite MUST cover at minimum these categories, each in its own test class or cohesive set of test methods:

- **Lexer**: every token class, both string literal forms, numeric literal forms, every operator, every punctuation character, rejection of unknown punctuation, keyword tokenization (`and`, `or`, `not`, `lt`, `lte`, `gt`, `gte`), and the removal of the symbolic tokens (`&&`, `||`, `!`, `<`, `>`, `<=`, `>=`).
- **Parser / AST shape**: every operator's AST node, precedence (including `not` binding tighter than comparisons and comparisons tighter than `and`/`or`), parentheses overriding precedence, arbitrary nesting, collection literals, `null`/`true`/`false` literals, `$this`/`$argN`/`$return` atoms, dot+index paths, keyword-vs-property-name disambiguation (e.g. `$arg0.and` parses as a property, `notable` parses as a property name), rejection of `not(...)`.
- **Type-checker success**: String equality, numeric comparisons, `in` against typed collection literals, each function's accepted operand types, collection-flatten leaf typing, `$return` on non-void and on void.
- **Type-checker failure**: String vs numeric mismatch, unknown function, unknown property, raw `Collection` element type, Map root, wrong function arity, `in` with mismatched element type, `contains` with non-String/non-Collection first operand.
- **Evaluator**: every operator's runtime behaviour with matching operands; `and` and `or` short-circuit (verify the right term is NOT evaluated when the left short-circuits); `not` including `not null`.
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
- **WHEN** the test suite is complete
- **THEN** every operator (`==`, `!=`, `lt`, `gt`, `lte`, `gte`, `in`, `and`, `or`, `not`, `+`, `-`, `*`, `/`, `%`) MUST have at least one test asserting its runtime behaviour with matching operand types

#### Scenario: Symbolic operators are rejected with tests
- **WHEN** the test suite is complete
- **THEN** each symbolic form (`&&`, `||`, `!`, `<`, `>`, `<=`, `>=`) MUST have at least one test asserting the expression fails to parse with an "unknown operator" category

#### Scenario: Every function has runtime coverage
- **WHEN** the test suite is complete
- **THEN** every function (`size`, `contains`, `like`, `ilike`, `icontains`) MUST have tests covering its documented behaviour including null operands and edge cases

#### Scenario: Short-circuit is verified
- **WHEN** `A and B` is evaluated and `A` is false
- **THEN** `B` MUST NOT be evaluated (verified by a leaf that records evaluation)
- **WHEN** `A or B` is evaluated and `A` is true
- **THEN** `B` MUST NOT be evaluated

#### Scenario: Every parse-failure category is covered
- **WHEN** the test suite is complete
- **THEN** each failure category (syntax error, unknown function, unknown operator/punctuation, unresolved property, type mismatch, raw collection, Map root, wrong function arity) MUST have a test asserting that `ExpressionCompileException` is thrown with the correct block id and category
