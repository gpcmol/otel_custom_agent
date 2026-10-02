# Condition DSL — current contract

The optional `expr` attribute on `<enrich>` is compiled at startup/reload and evaluated once at a
configured exit point. A failed parse or type check disables only that expression, logs one startup
warning, and makes the block unconditional.

## Syntax

The current XML-safe operators are `and`, `or`, `not`, `==`, `!=`, `lt`, `lte`, `gt`, `gte`, `in`,
`+`, `-`, `*`, `/`, and `%`. The symbolic boolean and ordered comparison operators (`&&`, `||`,
`!`, `<`, `>`, `<=`, `>=`) are rejected. Parentheses, string/numeric/boolean/null literals,
collection literals, and `$this`/`$argN`/`$return` property paths are supported.

Supported functions are `size`, `contains`, `like`, `ilike`, and `icontains`. `like` and `ilike`
use `%` and `_` SQL-style wildcards; `ilike` uses ASCII-oriented case folding. `icontains` compares
whole strings case-insensitively, or collection elements individually; it is not substring search.

## Evaluation

Evaluation uses the compiled AST and shared `ValueResolver` accessors. `and` and `or` short-circuit.
Null-safe behavior is defined as follows: `x == null` and `x != null` test the reference,
`size(null)` is zero, and other operations with required null operands evaluate to false. Runtime
evaluation failures evaluate to false and never escape into the application.

An expression that evaluates false skips static attributes, dynamic attributes, span lookup,
fallback-span creation, and the reentrancy guard.

## Deliberate V1 limitations

The current implementation type-checks public reflected scalar properties and array types. Generic
collection element-type inference and complete arithmetic type propagation are not yet reliable and
are not part of the current supported contract. Maps remain unsupported. These are future work,
not hidden requirements of the current agent.
