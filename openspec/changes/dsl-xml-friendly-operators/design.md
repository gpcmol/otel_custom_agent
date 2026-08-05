## Context

The V1 condition DSL (proposed in `add-condition-dsl`) spells its boolean and comparison operators with XML-hostile symbols: `&&`, `||`, `!`, `<`, `>`, `<=`, `>=`. Since `expr` is an XML attribute value, every such expression must be entity-escaped (`&amp;&amp;`, `&lt;=`) inside config files — a constant source of hand-editing mistakes and unreadable configs. `add-condition-dsl` is proposed but not yet implemented; there are no shipped configs using the DSL, so the operator vocabulary can be redefined before first release with zero real-world migration.

The parser is a hand-written recursive-descent parser in `org.otel.agent.expr.parser` (JDK only, no regex, no libraries). It tokenizes the expression, parses into a sealed AST (`And`, `Or`, `Not`, `Compare`, `In`, `Call`, literals, properties), type-checks at startup, and evaluates via a zero-allocation interpreter. This change only touches the lexer/parser surface; the AST node set, type-checker, and interpreter are unchanged.

## Goals / Non-Goals

**Goals:**

- Replace `&&`/`||`/`!`/`<`/`>`/`<=`/`>=` with `and`/`or`/`not`/`lt`/`gt`/`lte`/`gte` in the DSL grammar, so expressions fit verbatim in XML attributes with zero entity escaping.
- Treat `and`, `or`, `not` as keywords: `not` is a prefix keyword requiring an expression operand; the `not(...)` function-call form is rejected.
- Preserve the existing precedence *structure*: function calls > `not` > comparisons > `and` > `or`, with parentheses overriding.
- Keep the AST node set, type-checking rules, fail-safe semantics, and the interpreter byte-for-byte unchanged.

**Non-Goals:**

- No new operators or functions (`in`, arithmetic, `size`/`contains`/`like`/`ilike`/`icontains` stay exactly as in `add-condition-dsl`).
- No support for the old symbolic forms as aliases — they are removed, not deprecated.
- No XML-escaping helper or DSL-to-escaped-string tooling; the grammar change makes escaping unnecessary.
- No runtime changes: the hot path (`Condition.eval`, `EnrichmentRuntime.gate`) is untouched.

## Decisions

**D1. Keywords are recognized contextually by the parser, not by the lexer.**
The lexer produces a single `IDENT` token type; the parser matches `and`, `or`, `not`, `lt`, `lte`, `gt`, `gte` against the IDENT's text only in positions where an operator or prefix is expected (after a complete term for infix operators; at the start of a term for `not`). Consequences:
- Greedy identifier scanning already makes `notable` a single IDENT ≠ `not`, so word-boundary collisions cannot occur inside longer identifiers.
- Property segments after `.` or `[` are parsed as path segments, never as keywords — `$arg0.not`, `$arg0.and` remain valid property references.
- Function names are matched from the fixed vocabulary only when IDENT is followed by `(`, so `and(...)`/`or(...)`/`not(...)` never resolve to functions.
This is the minimal-change approach: no new token types, one keyword table consulted at two parser points.

**D2. `not` is parsed as a prefix production `notExpr := not notExpr | comparisonExpr`.**
This slots `not` between function calls and comparisons in the precedence chain, matching the requested grammar. No lookahead or backtracking needed; `not not ...` composes naturally (`not not $x` parses).

**D3. `not(...)` is rejected with an explicit check.**
After consuming the `not` keyword, the parser checks whether the next token is `(`; if so it fails with the "function-call form not allowed" category instead of descending into a parenthesised expression (where the trailing `, 'x%'` would anyway be a syntax error). Explicit check gives the user a precise message instead of a confusing one.

**D4. Symbolic operator tokens are deleted from the lexer.**
`&&`, `||`, `!`, `<`, `>`, `<=`, `>=` are no longer produced as tokens; the characters fall into the existing unknown-punctuation error path. Tests assert their rejection. `==`, `!=`, `(`, `)`, `[`, `]`, `,`, `.`, `+`, `-`, `*`, `/`, `%`, `$`, quotes are unchanged.

**D5. Token→operator mapping changes only in the parser.**
`lt`/`lte`/`gt`/`gte` map to the existing `Compare.Op` enum values (`LT`, `LTE`, `GT`, `GTE`) that the type-checker and interpreter already consume; `and`/`or`/`not` map to the existing `And`/`Or`/`Not` record nodes. Zero changes in `org.otel.agent.expr.node` and `org.otel.agent.expr.eval` — this is the core reason the change is small.

## Risks / Trade-offs

- [Breaking grammar for symbolic forms] → No shipped configs exist (`add-condition-dsl` unimplemented); the per-block fail-safe silently disables any future config that slips a symbolic operator in, with one startup warning.
- [Implementation-order coupling with `add-condition-dsl`] → Both changes edit the same parser and test files. Apply order: `add-condition-dsl` first (creates the parser + test tree), then this change rewrites the affected lexer/parser tests and updates the grammar examples. Tasks below are written against the `add-condition-dsl`-created tree.
- [Keyword collisions with property/function names in future DSL versions] → Contextual recognition (D1) already resolves `$arg0.not`-style paths; a user-facing escape for bare keyword identifiers would only matter if the grammar ever grows bare-identifier operands (not in V1; V2 topic).
- [Ambiguous `lte` vs property named `lte` in comparison position] → In comparison-operator position an IDENT must be one of the four comparison keywords, so no ambiguity exists; elsewhere (after `.`) it is a property name.

## Migration Plan

1. Land `add-condition-dsl` (parser, AST, type-checker, interpreter, test tree with symbolic-operator tests).
2. Land this change: update lexer (drop symbolic tokens), parser (keyword table, `not` prefix production, `not(` check), rewrite affected tests to word operators, add rejection tests for symbolic forms, update grammar examples in the benchmark app config and docs.
3. No runtime migration: `Condition`/`ExitPoint`/config reload semantics unchanged; only expression strings in config files change form.
4. Rollback: revert the parser change; no data or wire-format implications.

## Open Questions

- None blocking. If `add-condition-dsl`'s implementation had already started with symbolic operators in committed configs, those configs must be rewritten to word forms before this change lands (not the case today).
