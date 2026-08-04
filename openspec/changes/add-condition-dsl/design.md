## Context

The agent enriches OpenTelemetry spans at configured exit points (`<enrich class method>`).
Today, enrichment fires unconditionally for every configured exit point — every
`Garage.park` exit writes its static and dynamic attributes, whether or not the
runtime state is interesting. Teams have no declarative way to say "only enrich
this span when the car is a BMW" or "skip enrichment for anonymous tenants". The
result is either over-enrichment (noise, cardinality, PII leakage) or
under-enrichment (teams omit rules they actually need because they cannot gate
them).

The hot path is already cheap: the exit-point model cut enrich calls ~10×
(150 `setAttribute` per request → 15) by firing once per span-producing method
exit, not per getter. Adding one short expression walk per exit event — on top
of an already ~10× reduced event rate — is a rounding error versus today's
`AccessorCache`-lambdified attribute resolution. The design space is wide, but
the user's V1 grammar deliberately covers the 90–95% of conditional-enrichment
use cases (tenant/region gating, business-event labeling, PII omission, derived
booleans) while staying parseable and type-checkable at startup.

Three constraints shape the design:

1. The path-roots vocabulary (`$this`, `$argN`, `$return`) is already established
   and bound by the enclosing `<enrich class method>` block. Introducing a new
   binding convention (`class`-qualified paths, bare `this`) reintroduces the
   ambiguity the exit-point model eliminated and forces runtime type guessing —
   explicitly rejected by the user.
2. The codebase is religious about "no runtime DSL, no runtime string parsing"
   (`draft/spec.md` §14, `exit-point-enrichment/design.md` Non-Goals). The
   expression MUST compile to an immutable typed AST at startup, and the hot
   path MUST walk it without interpretation.
3. No new dependencies (`implement-trace-attribute-enrichment/design.md`:46).
   The expression parser is hand-written recursive descent using only JDK APIs.
   `like`/`ilike` use `String.startsWith`/`endsWith`/`contains` + char checks,
   not `java.util.regex` — zero allocation on the hot path, JIT-inlineable.

## Goals / Non-Goals

**Goals:**

- Add an optional `expr` attribute to `<enrich class method>` that gates the
  whole block (static + dynamic attributes) by a boolean expression evaluated
  once at method exit.
- Provide a V1 expression grammar (atoms, comparisons, boolean operators, `in`,
  `+ - * /`, `size`/`contains`/`like`/`ilike`/`icontains`, arbitrary nesting
  with parentheses) compiled at startup to a typed AST whose leaves reference
  the existing lambdified `AccessorCache`.
- Type-check each leaf at parse time against the reflected return type of the
  referenced getter, so the hot path does zero type guessing and zero coercion
  (per user requirement #6: "het moet niet raden wat het target type is").
- Fail safe per block: a parse failure, unresolved property, or type mismatch
  silently disables the expression for that block (block behaves as if `expr`
  were absent) and emits exactly one startup warning.
- Preserve existing behavior: configs without `expr` are 100% unchanged.
- Keep the hot path O(1)-ish: one short AST walk per exit event after the
  `ExitPointIndex.find` short-circuit, before any span/attribute work.

**Non-Goals:**

- Value expressions (Role B): `expr` only gates; it does not produce an
  attribute value. `<attribute>` still uses `path`. Derived attributes
  (`electric ? "EV" : "ICE"`) and PII masking (`mask(email)`) are out of scope.
- Top-level `<condition>` or a per-configuration global gate. The user explicitly
  chose `expr` on `<enrich>` itself over a separate element. Global gates are
  per-block expressions today; a shared top-level form is a V2 if recurrence
  becomes painful.
- Regex anythings — `like`/`ilike` use SQL wildcards only.
- Element-level predicates / lambda syntax in `contains` (e.g.
  `contains($arg0.passengers, p -> p.name == "Piet")`). Only literal-element
  `contains` against a flattened collection/`String` is supported;
  `$arg0.passengers.name` flattens via the existing `ValueResolver` and the
  result is a `List<String>` that `contains(nameLit)` can test.
- Runtime configuration reload of the expression — the same reload semantics
  as the rest of the configuration apply (`EnrichmentRuntime.reloadFromBridge`
  already rebuilds `ExitPointIndex`; the `Condition` AST lives on `ExitPoint`
  and is rebuilt with it).
- Type inference for nested property paths beyond what reflection of the
  declared getter return type gives. If a getter returns `List<Person>` and
  the next segment is `.name`, the type-check follows
  `ValueResolver`'s flatten semantics and types the leaf as `List<String>`.
  Deeply generic / erased types use `Object` fallback — the comparison then
  fails the type-check at parse time and silently disables the expression.

## Decisions

### Decision 1: `expr` is a plain attribute on `<enrich>`, not a child element

**Choice:** `<enrich class="com.example.Garage" method="park" expr="...">`. The
expression is optional; absent = unconditional (today's behavior).

**Alternatives considered:**

- *`<when>` child element inside `<enrich>`.* Adds a new XML element for one
  optional string. Rejected: same binding, more XML noise, one more validation
  path.
- *Top-level `<condition><expr .../></condition>` outside `<dynamic>`.* The user
  tried this form first; binding `this`/`$arg0` requires a `class` attribute
  that re-states what `<enrich>` already declares, and `$arg0` is ambiguous
  when multiple `<enrich>` blocks exist. Rejected: duplicates the exit-point
  binding, reintroduces the ambiguity the exit-point model eliminated.
- *Per-`<attribute>` expression.* Would let one block mix gated and ungated
  attributes — powerful but complicates the AST and the JIT-ability story
  (per-attribute evaluation). YAGNI for V1; can be added later as a second
  optional attribute on `<attribute>` without breaking the block-level `expr`.

**Rationale:** One optional attribute, one parse slot, one early return on the
hot path. Backward compatible by construction: an `<enrich>` without `expr`
parses identically to today.

### Decision 2: Roots are `$this` / `$argN` / `$return`, identical to attribute paths

**Choice:** The expression atoms for object navigation are exactly the existing
path roots, followed by the existing `PathSegment` grammar (`prop`,
`prop[idx]`, dot-chained). The expression and the block's `<attribute path=…>`
children share the same accessor resolution and the same `AccessorCache`
entries — e.g. `$arg0.brand` in the expression and `$arg0.brand` as an
`<attribute path>` reuse the same lambdified `Accessor`.

**Alternative:** Allow bare `this` and class-qualified paths
(`com.example.Car.brand`), like the original user example. Rejected: the
class-qualified form is a relic of the abandoned per-instance model and has
no natural binding in the exit-point model (which argument is the Car? which
is the Garage?). Bare `this` collides conceptually with `$this` and only
covers the receiver. Reusing `$`-roots ties the expression to exactly the
vocabulary the block already understands.

**Rationale:** Zero new binding machinery. The expression and the attribute
paths see the same world. Types can be inferred exactly the way `ValueResolver`
traverses them — getter return types are already part of the accessor model.

### Decision 3: Parse-time typed compilation — no runtime type guessing or coercion

**Choice:** At startup, the parser reflects on the return type of each getter
referenced by an expression leaf and chooses a fixed typed operator per AST
node. Concretely:

- `$arg0.brand == "BMW"` parses to `Comparison.Eq(Property, StringLiteral)`.
  The parser reflects `Garage.park`'s parameter 0 type (say `Car`), then
  `Car.getBrand()`'s return type (`String`). The leaf is typed `String`, the
  comparison is compiled as `String.equals`, and the AST node records
  "operator = String equals".
- `size($arg0.orders) > 5` reflects `Car.getOrders()` → `List<Order>`; the
  `size` function's return type is `int`; the comparison compiles to
  `int > int`.
- `$arg0.passengers.name` flattens to `List<String>` (matching
  `ValueResolver` collection semantics); `contains(..., "Piet")` compiles to
  `List.contains(String)`.
- `$arg0.mileage in [0, 100000]` reflects `Car.getMileage()` → `Long`; the
  collection literal compiles to `long[]` with primitive equality.

Type mismatches are **startup errors**: `$arg0.brand == 3` where `brand` is
`String` triggers a type-check failure. Per the user's failure-handling
requirement, the failure **silently disables** the expression for that block
(block becomes unconditional) and emits one warning at agent startup. The
configuration overall stays enabled; the remaining blocks and attributes are
unaffected.

**Alternative:** Runtime coercion (treat `String == int` as always false, or
attempt unboxing). Rejected: the user explicitly required "het moet niet
raden wat het target type is" — silent runtime coercion costs cycles and hides
config bugs. Better to surface type mismatches as startup warnings.

**Rationale:** Aligns with the "no runtime DSL" constraint and the user's
"no guessing" requirement. The interpreter on the hot path becomes a
mechanical switch over a typed AST — no `instanceof`, no casts, no coercions.
The JIT can inline the whole tree the way it inlines `ValueResolver` today.

### Decision 4: AST is a sealed `Condition` hierarchy evaluated by a small interpreter

**Choice:**

```
sealed interface Condition { boolean eval(EvalContext ctx); }
record And(List<Condition> terms)          implements Condition
record Or(List<Condition> terms)           implements Condition
record Not(Condition term)                 implements Condition
record Compare(Op op, Leaf left, Leaf right) implements Condition  // == != < <= > >=
record In(Leaf value, List<Leaf> literals) implements Condition
record Call.BoolCall(...)                   implements Condition  // size,contains,like,ilike,icontains

sealed interface Leaf { Object read(EvalContext ctx); }   // typed at compile time
record Literal(Object value, Class<?> type)     implements Leaf
record Property(RootSource root, List<PathSegment> segs, Class<?> leafType) implements Leaf
```

`Property` leaves resolve via the existing `ValueResolver.resolve(root, segs)`
— same code path as `<attribute>` resolution, same `AccessorCache` entries.
The interpreter never string-parses; each node is a fixed record with typed
operands.

**Alternative considered:** Full `MethodHandle` cascade (compile each
expression to one `MethodHandle` via `filterArguments`/`guardWithTest`/
`collectArguments`, lambdify once to a `BooleanSupplier`). Explored in
exploration; rejected for V1 because null-handling and numeric-vs-string
operator dispatch turn the MH assembly into a rabbit hole — and the exit-point
model already cut enrichment 10×, so the per-event AST walk is negligible. MH
cascade is a documented V2 escape hatch if a profiler ever proves the walk hot.

**Alternative considered:** ASM bytecode generation to a per-expression
generated class. Rejected: `implement-trace-attribute-enrichment/design.md:46`
explicitly forbids bytecode dependencies, and ASM is not a current project
dependency.

**Rationale:**

- Sealed records + pattern-match switch is the codebase's existing style
  (`RootSource`, `PathSegment`).
- The interpreter is ~150–250 LOC for the V1 grammar; each operator is one
  case branch with Java primitives.
- `Property` leaves reuse `ValueResolver` literally — no second accessor
  machinery.
- JIT sees a small virtual call chain per node and inlines aggressively; this
  is the same pattern that makes `ValueResolver` cheap today.
- ~7-node tree (a fully nested `(A && (B || C) && !D)`) = 7 virtual calls per
  exit event — comparable to one dynamic `<attribute>` resolve. The
  exit-point cut already reduced such resolves to one per span; adding one
  more walk per exit event adds negligible cost.

### Decision 5: `like`/`ilike` use SQL wildcards, never regex

**Choice:** `like($arg0.brand, "BMW%")` matches strings starting with `"BMW"`.
`%` = any sequence (incl. empty), `_` = exactly one character. Escaping is
**not** in V1 (YAGNI; if needed later, `\%` and `\_`).

Implementation is a hand-written matcher translating the pattern into
`startsWith`/`endsWith`/`contains` and per-char equality, with zero
allocation and a tight per-char loop that the JIT inlines:

```
pattern "BMW%"   → s.length() >= 3 && s.startsWith("BMW")
pattern "%iet"   → s.endsWith("iet")
pattern "%Pie%"  → s.contains("Pie")
pattern "Pi_t"   → s.length() == 4 && s.charAt(0)=='P' && s.charAt(1)=='i'
                                     && s.charAt(2) == '_' wildcard && s.charAt(3) == 't'
```

`ilike` is the same with per-character case-folding (reuse the case-fold logic
that backs `equalsIgnoreCase` in the JDK — no allocation).

**Alternative:** `java.util.regex.Pattern.compile(...).matcher(s).matches()`.
Rejected: compiles a `Pattern` (or worse, allocates `Matcher`) per call → GC
pressure, JIT cannot inline, ~10–30× slower than the direct char loop. Also
violates the "no runtime interpretation" spirit.

**Alternative:** Restrict to exact `equals` only. Rejected: user explicitly
asked for `like`/`ilike`; SQL wildcards cover the practical cases
(starts-with, ends-with, contains, single-char wildcard) without regex cost.

**Rationale:** Same design philosophy as the rest of the DSL — typed,
alloc-free, JIT-friendly, fixed vocabulary. No extension mechanism.

### Decision 6: `icontains` is the only case-insensitive string/collection function

**Choice:** V1 function vocabulary is exactly:
- `size(collection)` → `int` (`Collection.size()`, array length, or `0` for
  null; no coercion).
- `contains(collectionOrString, literalElement)` → `boolean` — uses
  `Collection.contains` / `String.contains` semantics. The first argument is
  typed at parse time (must be `Collection<T>` or `String`); the second
  argument is a literal of the matching element type.
- `like(value, pattern)`, `ilike(value, pattern)` → `boolean` — SQL wildcard
  match, pattern is a string literal.
- `icontains(value, literal)` → `boolean` — case-insensitive
  `contains`/`equals` for both `String` and `Collection<String>` arguments. A
  string `icontains($arg0.brand, "bmw")` is equivalent to
  `equalsIgnoreCase` on a `String`, and on a `Collection<String>` it tests
  whether any element `equalsIgnoreCase` the literal.

`lower`/`upper` are **not** included. `equalsIgnoreCase` is folded into
`icontains` (the user's explicit rename) so there is one case-insensitive
function covering both string and collection-string membership.

**Rationale:** Small fixed vocabulary, no reflective method dispatch, no
extension points. `icontains` unifies the two case-insensitive use cases
(string compare, collection membership) under one name.

### Decision 7: Silent disable on parse failure, one startup warning

**Choice:** When the parser fails on an `expr` for any reason (syntax error,
unresolved property, type mismatch, unknown function, bad arity), the
expression is **silently disabled** for that `<enrich>` block — the block
behaves as if `expr` were absent — and the agent emits exactly **one warning
at startup** identifying the block (`class#method`) and a short failure
category (e.g. "syntax error", "unknown function 'foo'", "type mismatch:
String vs Long"). No runtime logging. The configuration overall stays enabled.

**Alternative:** Fail the whole configuration and disable the extension (the
existing `ConfigurationException`-propagation pattern). Rejected by the user:
one block's bad expression should not take down the rest of the enrichment.
This mirrors the existing "one broken rule must not block the remaining rules"
guarantee from `EnrichmentRuntime.write`'s per-rule try/catch, lifted to the
block level.

**Alternative:** Strict parse + refuse startup. Rejected: breaks production
configs that add `expr` on one block, leave an old block with a typo, etc.
The user's explicit ask: "als de parser de expressie niet kan parsen dan kun
je doen alsof er geen expressie is (silently). evt. eenmalig een warning bij
de start van de agent."

**Rationale:** Matches the existing fail-safe philosophy (per-rule isolation,
no propagation) while making a typo non-fatal. One warning per bad block at
startup is enough to surface the problem without spamming hot-path logs.

### Decision 8: `$return` on void methods resolves to `null` and yields `false`

**Choice:** For an exit method declared `void`, `$return` in the expression
resolves to `null` at runtime. Comparisons involving `$return` short-circuit
to `false` (per the universal null-handling rule below) — the whole
expression evaluates to `false` and the block is skipped. No log, no error.
The parser accepts `$return` regardless of the method's return type (it
already validates method existence, not return type) and types the leaf as
the declared return type when non-void, `Object` when void; the runtime
null-handling rule covers the void case.

**Alternative:** Reject `$return` at parse time for void methods. Rejected
by the user ("$return op void mag je negeren alsof het er niet is"):
the existing accessor/`ValueResolver` machinery already resolves missing
roots to `null` silently, and the universal null-handling rule makes this
free.

**Rationale:** Unifies with the universal rule below and avoids the parser
depending on a return-type check (which is already a non-goal for ordinary
`<attribute>` paths — see `exit-point-enrichment/design.md` Decision 6: the
parser validates `$return` syntactically but does not enforce return type).

### Decision 9: Universal null-handling — any null operand makes a boolean false

**Choice:** For boolean operators (`==`, `!=`, `<`, `>`, `<=`, `>=`, `in`,
`&&`, `||`, `!`, `contains`, `like`, `ilike`, `icontains`, `size`-in-compare):

- If any required operand is `null` at runtime, the boolean result is
  `false`.
- `!null` is `true` (matched SQL three-valued logic simplified to two-valued:
  users who want "is not null" use `$x != null` which returns `true` for null
  per the next bullet; users who want "is null" use `$x == null`).
  Actually — to keep the rule trivially simple and predictable, we adopt:
  any comparison involving a `null` operand returns `false`, **except**
  `==`/`!=` against a `null` literal, which test for the null reference
  directly.
- `null == null` → `true`; `null != null` → `false`; `$x == null` → `true`
  iff `$x` resolves to `null`; `$x != null` → `true` iff `$x` resolves to
  non-`null`.

This set of rules lets users write the very common `$arg0.brand != null &&
$arg0.brand == "BMW"` (and we can short-circuit `&&` so the second test never
runs if the first is false).

**Rationale:** Predictable, no exceptions on the hot path, matches the existing
`ValueResolver` null-propagation rule (any null segment → `null` result) and
the existing "OTel attributes cannot represent null, so null means skip"
behavior of `SpanWriter`. One rule, consistent everywhere.

### Decision 10: Hot-path placement — after `find`, before span work

**Choice:**

```
EnrichmentRuntime.enrich(receiver, args, returned, methodName)
  → initialized / STATE.enabled check
  → rules = ExitPointIndex.find(receiver.getClass(), methodName)   (unchanged)
  → if rules.isEmpty() return                                       (unchanged)
  → if CONDITION != null && !CONDITION.eval(ctx) return             ← NEW
  → ENRICHING TL guard, Span.current() / fallback, write            (unchanged)
```

`EvalContext` is a tiny struct holding `(receiver, args, returned)` — the
same three values `EnrichmentRuntime.write` already uses to compute roots
via `rootOf`. No new allocation: the context is a stack-local record
allocated in `enrich` and passed by reference to the interpreter.

**Rationale:** Non-exit calls short-circuit at `find().isEmpty()` and pay
zero. Exit-point calls pay one AST walk. `false` short-circuits before
span lookup, before the reentrancy `ThreadLocal` is set, before fallback
span creation — minimal work for a gated-out event.

### Decision 11: `Condition` lives on `ExitPoint`; `ExitPointIndex` unchanged

**Choice:** `ExitPoint` gains a nullable `Condition` field. `ExitPointIndex`
and `ExitPointKey` are unchanged — the index still keys by `(Class, methodName)`
and still returns `List<ExitRule>`. After `find`, `EnrichmentRuntime` reads
the `ExitPoint`'s condition directly (a single reference read) and evaluates
it. If the lookup is a subclass fallback match, the same `ExitPoint` is
returned and the same condition applies — the subclass inherits the gate
naturally.

**Rationale:** The condition belongs to the block, and the block is the
`ExitPoint`. Putting the condition on `ExitRule` (per-attribute) would
require per-attribute evaluation — the user explicitly chose the block-level
gate. Putting it on `ExitPointIndex` would be global; rejected.

### Decision 12: The DSL lives in one isolated package group

**Choice:** All DSL code — lexer, parser, type-checker, AST types, and the
evaluator — lives under a single new top-level package group
`org.otel.agent.expr`, isolated from the existing `config`, `runtime`,
`telemetry`, and `instrumentation` packages. The only integration points
with the rest of the codebase are:

- `ConfigurationParser` calls `org.otel.agent.expr.parser.ExpressionParser`
  to compile an `expr` string into a `Condition` (cold path, startup only).
- `ExitPoint` (in `org.otel.agent.config.model`) holds a nullable
  `org.otel.agent.expr.Condition` reference — the only model type that
  crosses the package boundary.
- `EnrichmentRuntime` calls `condition.eval(ctx)` on the hot path — the
  only runtime call into the DSL package.

Internal package layout:

```
org.otel.agent.expr/
  Condition.java         sealed interface, boolean eval(EvalContext)
  Leaf.java              sealed interface, Object read(EvalContext)
  EvalContext.java       record (Object receiver, Object[] args, Object returned)
  ExpressionCompileException.java   typed failure carrying block + category
  node/
    And.java, Or.java, Not.java, Compare.java, In.java, BoolCall.java
    Literal.java, Property.java        (Leaf implementations)
  parser/
    ExpressionParser.java  entry: parse(expr, Class<?>, Method) -> Condition
    Lexer.java             tokenizer
    TypeChecker.java       parse-time reflective type inference + check
```

The AST node records implement `eval()` themselves (polymorphic, no visitor)
— this matches a small sealed hierarchy and keeps the interpreter code next
to the data it operates on. `Property` leaves delegate to the existing
`ValueResolver` (in `org.otel.agent.runtime.resolver`) via the shared
`AccessorCache` — one import from `runtime.resolver` is acceptable because
`Property` resolution reuses the exact same accessor machinery as
`<attribute>` paths; duplicating that logic would violate DRY and break the
shared-cache guarantee.

**Alternative:** Spread the DSL types across the existing package layout
(model types in `config.model`, parser in `config.parser`, evaluator in
`runtime`). Rejected: the user explicitly asked for the DSL to be "mooi
geïsoleerd" so it can be understood, tested, and potentially extracted as a
unit. Mixing it into `config.parser` would also conflate XML parsing with
expression parsing — the existing `ConfigurationParser` is already
single-purpose for XML.

**Alternative:** A single flat package `org.otel.agent.expr` with no
sub-packages. Acceptable for a smaller feature; the `node` and `parser`
sub-packages are used because the node records (≈8 files) and the
parser/type-checker (≈3 files) are each cohesive groups worth naming. The
top-level package holds only the 4 crossing types.

**Rationale:** Clean isolation meets the codebase's "one clear
responsibility per package" rule (`draft/spec.md` §13). The DSL is a
self-contained feature with a fixed vocabulary; isolating it keeps the
existing packages focused and makes the test suite a single cohesive
`org.otel.agent.expr` test tree.

### Decision 13: The evaluation point is one named, well-documented method

**Choice:** `EnrichmentRuntime` exposes one private method that is the
single, obvious place where the gate is evaluated:

```java
// org.otel.agent.runtime.EnrichmentRuntime
//
// The single hot-path entry point for the condition DSL. Returns true when
// the block should enrich (condition absent, disabled, or evaluates true);
// returns false when the block must be skipped. This is the ONLY place the
// DSL is evaluated at runtime — the parser only runs at startup/reload.
private static boolean gate(
    final ExitPoint exitPoint,
    final Object receiver,
    final Object[] arguments,
    final Object returned) {
  final Condition condition = exitPoint.condition();
  if (condition == null) return true;
  final EvalContext ctx = new EvalContext(receiver, arguments, returned);
  return condition.eval(ctx);
}
```

`EnrichmentRuntime.enrich` calls `gate` immediately after
`ExitPointIndex.find` returns the `ExitPoint` (the lookup is extended to
return the `ExitPoint` itself, not just the rules list — see Decision 11)
and immediately before the reentrancy `ThreadLocal` is set. A `false`
return short-circuits the whole event. One call site, one method, one
comment block explaining the contract — the evaluation point is obvious to
any reader.

**Rationale:** The user explicitly asked for "een duidelijke plek" where the
DSL is evaluated. A named method with a Javadoc contract is clearer than an
inline `if` expression in `enrich`'s body. It also makes the gate
unit-testable in isolation from span/attribute machinery — a test can call
`gate` (or a package-private equivalent) directly with a hand-built
`ExitPoint` and assert the boolean result, without spinning up the OTel
global tracer or a fake span. The named method is the test seam.

### Decision 14: Test-first development with comprehensive DSL coverage

**Choice:** The DSL is implemented test-first. Before any production code in
`org.otel.agent.expr`, the test tree `src/test/java/org/otel/agent/expr/`
is written covering every observable behaviour of the V1 grammar. The tests
run red against an empty package, then production code is added until the
suite is green. No production DSL file is committed without a failing test
that necessitates it.

The test suite MUST cover at minimum:

- **Lexer**: every token class, both string literal forms, numeric literal
  forms, every operator, every punctuation character, rejection of unknown
  punctuation.
- **Parser / AST shape**: every operator's AST node, precedence (left-to-right
  evaluation order), parentheses overriding precedence, arbitrary nesting
  (`A && (B || C) && !D`), collection literals, `null` literal, `true`/
  `false` literals, `$this`/`$argN`/`$return` atoms, dot+index paths.
- **Type-checker**: every type-check success path (String==, numeric
  comparisons, `in` against typed collection literals, each function's
  accepted operand types, collection-flatten leaf typing via
  `List<Person>` → `.name` → `List<String>`, `$return` on void typed as
  `Object`), and every failure category (String vs numeric mismatch,
  unknown function, unknown property, raw `Collection` element type, Map
  root, wrong arity for a function, `in` with mismatched element type,
  `contains` with non-String/non-Collection first operand).
- **Evaluator**: every operator's runtime behaviour with matching operands;
  short-circuit of `&&` (right term not evaluated when left is false) and
  `||` (right term not evaluated when left is true); `!` including `!null`;
  null-handling rules (`== null` / `!= null` direct reference test, any
  other comparison with null operand → false, `size(null)` → 0, `contains`
  with null haystack → false, `like`/`ilike`/`icontains` with null →
  false); runtime exception swallowed → false.
- **`like`/`ilike`**: every wildcard form (`%` prefix, `%` suffix, `%` both
  ends, `_` single char, mixed `%`/`_`, empty pattern, pattern longer than
  value, no-match), case-sensitivity of `like`, case-insensitivity of
  `ilike` with ASCII fold, no `java.util.regex` allocation (assertable via
  a micro-benchmark or a flag set when the regex classes are touched —
  simpler: assert the result against the documented semantics and trust the
  implementation to use `startsWith`/`endsWith`/`contains`).
- **`contains`/`icontains`**: `String.contains` (substring), `Collection.contains`
  (element equality), flattened-collection `contains` via path navigation,
  `icontains` whole-string equality on `String`, `icontains` element-wise
  on `Collection<String>`, `icontains` does NOT substring-match (negative
  test), type-mismatch rejection at parse time.
- **`size`**: `Collection.size()`, array length, `null` → 0, used in
  comparison (`size($x) > 5`).
- **`$return` on void**: leaf resolves to null, comparison yields false,
  `$return == null` yields true, no exception raised.
- **Parse-failure categories**: syntax error (trailing operator, unclosed
  parenthesis, empty expression), unknown function, unknown operator/
  punctuation, unresolved property, type mismatch (each operator's
  mismatch), raw collection, Map root, wrong function arity. Each failure
  MUST produce a `ExpressionCompileException` with the block id and a
  named category, which `ConfigurationParser` catches to produce a
  `null` `Condition` and one startup warning.
- **Integration with `ConfigurationParser`**: valid `expr` produces a
  non-null `Condition` on the `ExitPoint`; invalid `expr` produces a
  `null` `Condition` and the block enriches unconditionally; duplicate
  `<enrich>` detection still fires regardless of `expr` values; unknown
  `expr` attribute is rejected when the allowed set is not extended
  (regression guard).
- **Integration with `EnrichmentRuntime` gate**: `false` condition skips
  the whole block (zero `setAttribute`, zero fallback span, reentrancy
  `ThreadLocal` not set); `true` condition enriches normally; `null`
  condition retains today's behaviour; `$return`-on-void condition skips
  the block; subclass receiver inherits the declared block's condition.

Tests use the project's existing test framework (JUnit 5 per the existing
test tree). Test fixture classes are small Java records/POJOs with the
getters the DSL reflects on (`getBrand`, `getPassengers`, `getName`,
`getOrders`, `getElectric`, etc.) — the existing benchmark fixtures may be
reused where they already expose the needed getters.

**Rationale:** The user explicitly asked for tests first and comprehensive
situation coverage. A DSL is the kind of feature where edge cases
(null-handling, precedence, type-check corner cases, SQL wildcard forms,
short-circuit) are the entire value — getting them wrong silently is the
worst outcome. Test-first forces the grammar and semantics to be specified
by executable examples before any implementation choice biases them, and
the test tree becomes the documentation of the DSL's behaviour. The
"comprehensive situations" requirement is satisfied by the per-category
coverage list above, which the tasks phase breaks into concrete test
classes.

## Risks / Trade-offs

- **[Risk] Reflection-driven type inference for collection-flatten chains
  requires generic type info that the JVM erases.** A getter returning
  `List<Person>` lets the parser infer `Person`, then `.name` infers
  `String`. But a getter returning raw `List` (no generics) gives `Object`;
  the type-check fails and the expression is silently disabled.
  → *Mitigation:* document that raw collections are unsupported in
  expressions (same limitation as today's `<attribute path>` which also
  relies on flatten semantics). The type-check is a structural check, not a
  guess — if it can't prove the type, it disables, which is always safe.
- **[Risk] Silent disable hides real config bugs.** A typo in a property
  name silently disables the gate and the block over-enriches.
  → *Mitigation:* the one startup warning names the block and the failure
  category. The warning is enabled at startup by default (not behind a debug
  flag). The expected user workflow is to scan startup logs after a config
  change.
- **[Risk] Parse-time reflection adds startup cost.** Each leaf reflects one
  getter. N blocks × M leaves = N*M reflections, all in the startup path.
  → *Mitigation:* the parser already reflects the exit-point class to verify
  method existence (ConfigurationParser.java:163). Reusing the same
  `Class<?>` for leaf type-checking adds cheap per-getter `getMethod` calls.
  Worth measuring once at benchmark time, not worth pre-optimizing for a V1
  feature whose total reflection count is bounded by config size (likely
  tens of getters).
- **[Risk] Encrypting literal-quote escaping inside XML attributes is
  fragile.** `<enrich ... expr="$arg0.brand == 'BMW'">` — single-quoted
  string literals inside a double-quoted XML attribute. XML attribute
  escaping requires `&quot;`/`&apos;` for the outer quote, single or
  double inside is fine as long as they differ.
  → *Mitigation:* the parser accepts both `'...'` and `"`...`"` string
  literals in the DSL, and the docs recommend `'...'` inside the
  double-quoted XML attribute. `expr="$x == 'BMW'"` reads cleanly;
  `expr='$x == "BMW"'` is equivalent but less common.
- **[Risk] `like`/`ilike` patterns with Unicode case-fold edge cases.**
  Per-character case-fold misses the German ß → SS expansion and a small
  set of locale-sensitive folds.
  → *Mitigation:* accept the limitation for V1 — `ilike` is for ASCII
  identifiers (tenant codes, brand codes, region tags). Document: "ASCII
  case-fold; Unicode edge cases (ß, Turkish I) are not covered."
- **[Risk] Type-check on subclass overrides.** If `<enrich class="Garage">`
  declares `expr="$arg0.brand == 'BMW'"` and a subclass overrides `park` to
  accept a supertype of `Car`, the subclass arg's `getBrand()` may not exist.
  → *Mitigation:* the type-check uses the declared `<enrich class>`'s method
  signature (`Garage.park`), exactly like today's `<attribute path>` does.
  Subclass overrides with different signatures are out of scope of the
  type-check; the null-handling rule covers it at runtime (missing getter →
  null leaf → `false`). Document.
- **[Trade-off] No escape sequences in `like`/`ilike` patterns.** A user
  who literally wants to match `"% discount"` cannot express it. Accepted
  for V1; add `\%` and `\_` if needed.
- **[Trade-off] Block-level gate only.** A block cannot mix gated and
  ungated attributes. Accepted for V1 — split into two `<enrich>` blocks
  for the same method … actually, the parser rejects duplicate
  `(class, method)` pairs, so this is impossible today. Document the
  workaround: use a single `expr` with `||` to combine conditions, and use
  `<attribute>` rules that always fire when the gate is true. Per-attribute
  expressions are a V2 — the `<attribute>` element already has `key` and
  `path`; adding optional `expr` there is non-breaking when needed.

## Migration Plan

1. **No migration required for existing configs.** `<enrich>` blocks without
   an `expr` attribute behave exactly as today. Existing configs continue
   to work unchanged.
2. **Optional adoption.** Add `expr="…"` to any `<enrich>` block that should
   be gated. Start with one block (typically the noisiest) and use a simple
   comparison (`$arg0.brand == 'BMW'`). Iterate.
3. **Failure visibility.** After a config change, scan agent startup logs for
   the one-time "expression disabled" warning. Fix typos/types as needed.
4. **Rollback.** Remove the `expr` attribute. No persisted state, no schema
   migration.

## Open Questions

1. **Expression complexity cap.** Should the parser enforce an AST node count
   limit (say, 50 nodes) to protect against pathological configs, or trust
   the user? V1 trusts; if a benchmark shows a multi-100-node expression
   measurably costing anything, add a cap later.
2. **`any`/`exists` predicate.** The `contains($arg0.passengers.name, …)`
   flattening works only when the path produces a `List<String>`. A predicate
   like `any(p -> p.age > 18, $arg0.passengers)` is V2. Defer until a real
   use case appears.
3. **Per-attribute `expr`.** A second optional `expr` on `<attribute>` for
   per-attribute gating is a non-breaking V2. Defer until block-level gating
   proves insufficient.
4. **Numeric literal types.** `123` is typed `long` or `int`?
   `123.0` is `double`. The parser should infer the narrowest type that
   matches the comparison's other operand (e.g. `mileage` is `Long`, so `123`
   becomes `long` literal). Defer to implementation; the type-check handles
   it.
5. **Static-block `expr`.** Should the global `<static>` block also accept
   an `expr` to gate all static attributes? V1: no (static attrs are
   applied to every exit point; gating them per exit point is per-`<enrich>`
   `expr` semantics on the exit point, not on static). Defer.