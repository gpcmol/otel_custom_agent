## Why

The V1 condition DSL from `add-condition-dsl` uses `&&`, `||`, `<`, `>`, `<=`, `>=` — symbols that collide with XML syntax. Because `expr` is an XML attribute, every expression today must be XML-escaped (`&amp;&amp;`, `&lt;`), which is error-prone, unreadable, and easy to get wrong by hand. Replacing those symbols with word operators (`and`, `or`, `lt`, `lte`, `gt`, `gte`, `not`) makes the DSL fully XML-attribute-friendly: expressions can be written verbatim in configuration files without any escaping.

## What Changes

- Replace the DSL's symbolic boolean/comparison operators with word operators:
  - `&&` → `and`
  - `||` → `or`
  - `!` → `not` (prefix keyword)
  - `<` → `lt`, `<=` → `lte`, `>` → `gt`, `>=` → `gte`
  - `==` and `!=` remain unchanged (already XML-safe).
- `and`, `or`, `not` become reserved keywords, not functions. `not` MUST be followed by an expression (function call, comparison, or parenthesised expression). `not(...)` function-call syntax is rejected.
- Precedence, highest to lowest: function calls → `not` → comparisons (`==`, `!=`, `lt`, `lte`, `gt`, `gte`) → `and` → `or`. Parentheses still override precedence.
- The symbolic forms (`&&`, `||`, `!`, `<`, `>`, `<=`, `>=`) are removed from the grammar; expressions using them fail to parse and fall into the existing per-block fail-safe (silently disabled + one startup warning).
- After this change, a config can carry `expr="$arg0.brand == 'BMW' and $arg0.year gte 2020 and not ilike($arg0.model, 'x%')"` unescaped.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `condition-dsl`: The V1 DSL grammar's operator vocabulary changes — `and`/`or`/`not`/`lt`/`lte`/`gt`/`gte` replace `&&`/`||`/`!`/`<`/`>`/`<=`/`>=`. `==`/`!=`/`in`/arithmetic/function syntax, precedence structure (with `not` slot), fail-safe semantics, type-checking, and runtime evaluation are unchanged. This delta amends the grammar requirements in `add-condition-dsl`; it is written as a MODIFIED delta against the same `condition-dsl` spec so the two archive cleanly in sequence.

## Impact

- **Parser**: lexer/parser changes in `org.otel.agent.expr.parser` — new token types for `and`/`or`/`not`/`lt`/`lte`/`gt`/`gte`, removal of the symbolic tokens, one new precedence level for `not`. Existing "unknown operator/punctuation" parse-error path already covers the removed symbols.
- **Grammar docs & type-checker**: AST node set unchanged (`And`, `Or`, `Not`, `Compare`); only token→node mapping changes. No evaluator changes.
- **Configs**: **BREAKING** for any config already using the symbolic operators — they must be rewritten to the word forms. Configs using only `==`/`!=` (or no `expr`) are unaffected. Since `add-condition-dsl` is not yet archived/implemented, no shipped config exists.
- **Tests**: lexer/parser tests updated for new tokens and precedence; negative tests for symbolic forms; keyword-vs-property-name disambiguation tests (`$arg0.year` vs `and`).
- **No new dependencies**, no changes outside `org.otel.agent.expr`.
