## Why

The agent enriches spans at configured exit points, but every configured exit point
fires unconditionally. Teams cannot gate enrichment on a runtime property of the
receiver, an argument, or the return value — so they either enrich spans that should
not carry business attributes, or omit rules they actually need. A small declarative
expression on the exit point covers the 90–95% of OpenTelemetry use cases that need
conditional enrichment: business-event labeling, tenant/region gating, PII omission,
and derived boolean properties. The expression is parsed and type-checked once at
startup and compiled to a typed AST whose leaves reuse the existing lambdified
`AccessorCache`, so the hot-path cost is one short tree walk per exit event —
negligible after the exit-point model already cut enrichment frequency ~10×.

## What Changes

- Add an optional `expr` attribute to the `<enrich class method>` configuration
  element. When present, the expression is evaluated once at the exit-point method
  exit, before any span or attribute work. A false result skips the whole block
  (static attributes and dynamic attributes for that exit point). When absent, the
  current unconditional behavior is preserved (100% backward compatible).
- Add a small expression DSL with this V1 grammar:
  - Atoms: `$this`, `$argN` (N=0..127), `$return` (existing path roots), dot+index
    navigation (existing `PathSegment` grammar), string literals (`'...'`/`"..."`),
    numeric literals, boolean literals (`true`/`false`), collection literals
    `[a, b, c]`.
  - Operators: `==`, `!=`, `<`, `>`, `<=`, `>=`, `in`, `&&`, `||`, `!`, and
    arithmetic `+`, `-`, `*`, `/`, `%`. Full parenthesised nesting
    (`A && (B || C) && !D`).
  - Functions: `size`, `contains`, `like`, `ilike`, `icontains`. `like`/`ilike`
    use SQL wildcard syntax (`%` any sequence, `_` one char); `ilike` is the
    case-insensitive variant. `icontains` is case-insensitive collection-or-string
    membership/contains. No regex.
- Parse the expression once at startup into a typed AST whose leaves reference
  the existing lambdified accessors. Type-check each leaf against the declared
  property's reflected return type at parse time; a type mismatch
  (e.g. `$arg0.brand == 3` where `brand` is `String`) is a configuration error that
  disables the expression for that block (see failure handling below). No runtime
  type guessing or coercion occurs.
- Treat `$return` on a `void` exit-point method as absent: the parser does not
  require a return type for the method, and at runtime a `$return`-rooted leaf
  resolves to `null` and the containing comparison evaluates to `false` (skip the
  attribute, no error, no log).
- On expression parse failure (syntax error, unresolved property, unsupported
  operator, type mismatch after parse-time type-check): **silently ignore the
  expression** for that `<enrich>` block — the block behaves as if `expr` were
  absent — and emit **exactly one warning at agent startup** identifying the
  block (`class#method`) and the failure category. No runtime logging. The static
  and dynamic attributes of that block remain unconditional, matching today's
  behavior. This is the same fail-safe pattern as the existing "invalid startup
  configuration disables this extension" rule, narrowed to a single block.
- Hot-path placement: evaluate the compiled expression in `EnrichmentRuntime.write`
  after `ExitPointIndex.find` returns non-empty rules and before any static or
  dynamic attribute work. A false result short-circuits the whole write for that
  event. Non-exit-point calls already short-circuit at `find().isEmpty()` and pay
  zero expression cost.
- Isolate the DSL in its own package group `org.otel.agent.expr` (with `node`
  and `parser` sub-packages) so the lexer, parser, type-checker, AST, and
  evaluator form one self-contained, independently testable unit. The only
  integration points with the rest of the codebase are:
  `ConfigurationParser` → `ExpressionParser.parse` (cold path), `ExitPoint`
  holding a nullable `Condition` reference, and `EnrichmentRuntime.gate` calling
  `condition.eval(ctx)` (hot path). The DSL reuses `ValueResolver` /
  `AccessorCache` for `Property` leaf resolution so the expression and the
  `<attribute>` paths share accessor cache entries.
- Implement the DSL test-first: the comprehensive `org.otel.agent.expr` test
  tree (lexer, parser shape, type-check success/failure, evaluator, null
  handling, `like`/`ilike` SQL wildcards, `contains`/`icontains`, `size`,
  `$return`-on-void, parse-failure categories, `ConfigurationParser`
  integration, `EnrichmentRuntime` gate) is written before production code and
  runs red against empty stubs; production code is added until the suite is
  green. The DSL is the kind of feature where edge cases (null-handling,
  precedence, type-check corner cases, SQL wildcard forms, short-circuit) are
  the entire value — test-first forces the semantics to be specified by
  executable examples before implementation choices bias them.
- Expose the gate as a single named, Javadoc-documented method
  `EnrichmentRuntime.gate(ExitPoint, receiver, arguments, returned)` — the one
  obvious place where the DSL is evaluated at runtime. The method is the test
  seam for the runtime gate and carries the contract comment describing when
  the block is enriched vs skipped.

## Capabilities

### New Capabilities

- `condition-dsl`: Optional boolean expression on a configured exit point that gates
  span enrichment for that exit point. Adds an `expr` attribute to `<enrich>`,
  a fixed-vocabulary expression grammar with parse-time typed compilation, and a
  hot-path AST evaluator that reuses the existing accessor cache.

### Modified Capabilities

- `exit-point-enrichment`: The `<enrich class method>` element gains an optional
  `expr` attribute. When the expression evaluates to `false` at method exit, the
  static and dynamic attributes of that block are skipped. When `expr` is absent
  or silently disabled by a parse-time failure, the block enriches unconditionally
  (current behavior). No change to the `<attribute>` children, path roots, or
  instrumentation matching — only one early return in the write path.

## Impact

- **Configuration**: new optional `expr` attribute on `<enrich class method>`.
  Existing configs without `expr` continue to work unchanged.
- **Parser**: `ConfigurationParser` parses the `expr` attribute, invokes a new
  expression parser/compiler, and attaches the compiled `Condition` AST to the
  `ExitPoint` (or `null` when absent or silently disabled). Startup validation
  resolves path roots against the already-resolved exit-point class and reflects
  on getter return types for the type-check. Parse failures are caught per-block
  and logged once as a warning, not propagated to disable the whole configuration.
- **Model**: `ExitPoint` gains a `Condition` field (nullable). `Condition` is a
  sealed interface over a small AST of records (`Literal`, `Property`,
  `Comparison`, `Boolean`, `Arithmetic`, `Ternary excluded`, `Call`, `In`).
- **Runtime**: `EnrichmentRuntime.write` evaluates the `Condition` once after
  `ExitPointIndex.find` and before static/dynamic writes; `false` skips the block.
  `$return` on void methods resolves to `null` and comparisons yield `false`
  silently.
- **No new dependencies**: the expression parser is hand-written recursive
  descent using only JDK APIs. No regex, no SpEL, no expression library. `like`/
  `ilike` translate SQL wildcards to `startsWith`/`endsWith`/`contains` + char
  checks (zero allocation, JIT-inlineable).
- **Tests**: parser unit tests (valid/invalid expressions, type-check failures,
  parse-failure-treated-as-absent), AST evaluator unit tests (each operator and
  function, null handling, void `$return`), `ConfigurationParserTest` extension
  for the `expr` attribute, and an integration assertion that a false expression
  skips attributes while a true expression emits them. The benchmark app config
  gains one gated `<enrich>` block to exercise the hot path.