## 1. Adapt the tests FIRST (test-first, red)

- [x] 1.1 Adapt `LexerTest`: update every operator assertion to expect `and`/`or`/`not`/`lt`/`lte`/`gt`/`gte` as plain `IDENT` tokens; flip the old symbolic tokens (`&&`, `||`, `!`, `<`, `>`, `<=`, `>=`) into unknown-punctuation rejection cases (`@`, `<=>`, `~` style)
- [x] 1.2 Adapt `ExpressionParserShapeTest`: rewrite AST-shape assertions to word operators (`A and B`, `A or B`, `not X`, `not (expr)`, `not func(...)`); assert precedence `not` > comparisons > `and` > `or`; assert `not(...)` is rejected; assert keyword-vs-property disambiguation (`$arg0.not`, `$arg0.and`, `notable` still parse)
- [x] 1.3 Adapt `TypeCheckerSuccessTest` / `TypeCheckerFailureTest`: replace `<`, `>`, `<=`, `>=` examples with `lt`, `lte`, `gt`, `gte`
- [x] 1.4 Adapt `EvaluatorTest` / `EvaluatorNullHandlingTest`: replace `&&`/`||`/`!` in short-circuit and null tests with `and`/`or`/`not`
- [x] 1.5 Adapt `SizeFunctionTest`, `ContainsIcontainsTest`, `LikeMatcherTest`, `ReturnOnVoidTest`, `ConfigurationParserExprTest`, and `EnrichmentRuntimeGateTest` expression strings to word operators; add a negative parse-failure case per removed symbolic form (`&&`, `||`, `!`, `<`, `>`, `<=`, `>=`)
- [x] 1.6 Run `mvn test` (or the project's test command): the suite MUST be red here — `ExpressionParserTest` still parses the symbolic forms — proving the tests encode the new grammar before any implementation change

## 2. Lexer: replace symbolic tokens with word tokens

- [x] 2.1 Remove the `&&`, `||`, `!`, `<`, `>`, `<=`, `>=` token productions from the lexer in `org.otel.agent.expr.parser`; `&`, `|`, `!`, `<`, `>` now fall through to the existing unknown-punctuation error path
- [x] 2.2 Re-run `LexerTest` until green (and, or, not, lt, lte, gt, gte lex as single `IDENT`; `notable` ≠ `not`; symbolic chars rejected)

## 3. Parser: word operators, precedence, and the `not` production

- [x] 3.1 Add a keyword table recognized contextually from `IDENT` text only where an operator or prefix is expected (never for path segments after `.`/`[`)
- [x] 3.2 Introduce the `not` prefix production `notExpr := not notExpr | comparisonExpr`; map `and`/`or` to the existing `And`/`Or` productions (lowest precedence), `lt`/`lte`/`gt`/`gte` to the existing `Compare.Op` enum values
- [x] 3.3 After consuming the `not` keyword, reject an immediately following `(` with the explicit "function-call form not allowed" failure category
- [x] 3.4 Re-run the `org.otel.agent.expr` suite until green (parser, type-checker, evaluator, null handling, functions, `$return`-on-void) — evaluator/type-checker should need NO code changes, only test strings

## 4. Spec examples, configs, and docs

- [x] 4.1 Update the benchmark app config in `config/agent-config.xml` to the word-operator form: `(... or ... ) and $arg0.mileage gt 0` and verify the XML matches
- [x] 4.2 Update `README.md` DSL reference and examples to the word operators; add a note that expressions are XML-safe and need no escaping
- [x] 4.3 Grep the repo for `<`, `>=`, `&&` inside `expr=`/`expr` attribute values (excluding `openspec/` change history) and rewrite any occurrences

## 5. Full-suite verification

- [x] 5.1 Run the full build (`mvn verify` or the project's test command) green
- [x] 5.2 Confirm the working benchmark (`scripts/bench.sh`) still passes with the word-operator config (enrichment_attributes: PASS)
