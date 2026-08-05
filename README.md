# Intro
This is a opentelemetry custom agent

# Purpose
Purpose is for development teams to define declarative configuration for dynamically adding proprties to spans

# Information
- draft folder - the first idea on paper
- openspec folder - from idea to spec
- app folder - example app to test the agent in real live
- src folder - the agent code

# How to run it?
- in app/: mvn clean install. This results in am example application 
- start the opentelemetry collector stub in app/ on port 4317
- in ./: gradle build
- run the run-agent.sh script to start the example application with the custom agent
- post data again the cars endpoint of the example application using app/car.http

# Configuration

Declare one **exit point** per span-producing method. Each `<enrich class method>` block bundles
the attribute rules that apply when that method exits. Paths start from a configurable root:
`$this` (receiver), `$argN` (Nth parameter, 0..127), or `$return` (return value). The rest of
each path uses the existing property/index grammar (e.g. `passengers[1].name`).

```xml
<configuration>
    <static>
        <attribute key="domain" value="cars"/>
        <attribute key="team" value="winning"/>
        <attribute key="environment" value="production"/>
        <attribute key="region" value="eu-west"/>
        <attribute key="service" value="garage"/>
    </static>
    <dynamic>
        <enrich class="com.example.Garage" method="park"
                expr="$arg0.brand == 'BMW' and ilike($arg0.model, 'x%')">
            <attribute key="brand" path="$arg0.brand"/>
            <attribute key="model" path="$arg0.model"/>
            <attribute key="year" path="$arg0.year"/>
            <attribute key="color" path="$arg0.color"/>
            <attribute key="licensePlate" path="$arg0.licensePlate"/>
            <attribute key="vin" path="$arg0.vin"/>
            <attribute key="mileage" path="$arg0.mileage"/>
            <attribute key="fuelType" path="$arg0.fuelType"/>
            <attribute key="transmission" path="$arg0.transmission"/>
            <attribute key="passengers" path="$arg0.passengers[1].name"/>
        </enrich>
    </dynamic>
</configuration>
```

Reads as: *"when `com.example.Garage.park` exits, if the first argument's `brand` equals `BMW`
and `model` matches `x%` (case-insensitive), read these properties and write them on the current
span."* One enrich event per span-producing method exit. The `expr` gate is optional — omit it
for unconditional enrichment.

### Conditional enrichment with `expr`

An `<enrich>` block MAY carry an optional `expr` attribute that gates the whole block. When the
expression evaluates to `false` at method exit, the block's static and dynamic attributes are
skipped. When `expr` is absent, the block enriches unconditionally.

The expression is parsed and type-checked once at startup against the declared exit-point class
and method signature. On the hot path it is evaluated as a compiled AST — no runtime string
parsing, no reflection, no type guessing.

### DSL reference

#### Path roots

| Root | Resolves to | Example |
|------|-------------|---------|
| `$this` | The receiver of the exit-point method | `$this.brand` |
| `$arg0` | The first method parameter (0-indexed, 0..127) | `$arg0.brand` |
| `$return` | The method return value (`null` on `void` methods) | `$return.status` |

Path segments follow the existing grammar: dot-separated property names with optional `[index]`
for arrays and lists. Example: `$arg0.passengers[1].name`.

#### Atoms

| Atom | Description | Example |
|------|-------------|---------|
| `$root.prop` | Property access from a path root | `$arg0.brand` |
| `'string'` or `"string"` | String literal | `'BMW'` |
| `42`, `3.14` | Numeric literal (long or double) | `42`, `3.14` |
| `true`, `false` | Boolean literal | `true` |
| `null` | Null literal (for null-checks) | `null` |
| `[a, b, c]` | Collection literal (for `in` operator) | `[0, 100000]` |

#### Operators (lowest to highest precedence)

| Operator | Description | Example |
|----------|-------------|---------|
| `or` | Boolean OR (short-circuits) | `$x == 'a' or $x == 'b'` |
| `and` | Boolean AND (short-circuits) | `$x == 'a' and $y == 'b'` |
| `not` | Boolean NOT (prefix) | `not $arg0.electric` |
| `==`, `!=` | Equality / inequality (any type) | `$arg0.brand == 'BMW'` |
| `lt`, `gt`, `lte`, `gte` | Numeric comparison | `$arg0.mileage gt 50000` |
| `in` | Collection membership | `$arg0.mileage in [0, 100000]` |
| `+`, `-`, `*`, `/`, `%` | Arithmetic | `size($arg0.orders) + 1` |
| `( )` | Parenthesised grouping | `($x or $y) and $z` |

Full nesting is supported: `A and (B or C) and not D`.

> **XML-friendly.** Every operator is a word (`and`, `or`, `not`, `lt`, `lte`, `gt`,
> `gte`) or already XML-safe (`==`, `!=`, `in`), so an `expr` attribute fits in a config
> file **without any entity escaping**. The old symbolic forms (`&&`, `||`, `!`, `<`, `>`,
> `<=`, `>=`) are not accepted and fall into the per-block fail-safe (silently disabled
> + one startup warning).

#### Functions

| Function | Description | Example |
|----------|-------------|---------|
| `size(coll)` | Collection/array size (`0` for `null`) | `size($arg0.orders) gt 5` |
| `contains(hay, needle)` | `String.contains` (substring) or `Collection.contains` (element) | `contains($arg0.tags, 'vip')` |
| `like(str, pattern)` | SQL `LIKE` — `%` = any sequence, `_` = one char (case-sensitive) | `like($arg0.brand, 'BM%')` |
| `ilike(str, pattern)` | SQL `ILIKE` — case-insensitive `like` | `ilike($arg0.model, 'x%')` |
| `icontains(hay, needle)` | Case-insensitive `equals` (String) or element-wise `equalsIgnoreCase` (Collection<String>) | `icontains($arg0.country, 'NL')` |

> **`icontains` does NOT do substring matching.** It compares whole strings (or whole
> collection elements) case-insensitively. For case-insensitive substring matching, use
> `ilike(hay, "%needle%")`.

> **`ilike` uses ASCII case-fold.** German ß and Turkish I are not correctly handled — V1
> targets ASCII identifiers (tenant codes, brand codes, region tags).

### Examples

```
# Only enrich BMWs
$arg0.brand == 'BMW'

# BMW X-series only (case-insensitive model prefix)
$arg0.brand == 'BMW' and ilike($arg0.model, 'x%')

# Premium customers: large order OR VIP tag
size($arg0.orders) gt 5 or contains($arg0.tags, 'vip')

# Gate on nested property with null-safety (built into all path access)
$arg0.customer?.country == 'NL'

# Mileage in a range
$arg0.mileage in [0, 100000]

# Negation with grouping
not ($arg0.brand == 'audi') and $arg0.electric

# Multiple conditions with parentheses
($arg0.brand == 'BMW' or $arg0.brand == 'VW')
  and $arg0.mileage gt 10000
  and ilike($arg0.fuelType, 'electric%')
```

### Failure handling

If the expression cannot be parsed or type-checked at startup, it is **silently disabled** for
that block — the block enriches unconditionally (as if `expr` were absent) — and the agent emits
exactly **one warning at startup** naming the block (`class#method`) and the failure category.

| Failure category | Cause |
|------------------|-------|
| `syntax error` | Malformed expression (unclosed parenthesis, trailing operator, unknown token) |
| `type mismatch` | Operand types don't match (e.g. `String == Long`) |
| `unknown function` | Function name not in the fixed vocabulary |
| `unknown property` | Property not found on the declared class via getter/field |
| `erased collection type` | Raw `Collection` without generic type parameter |
| `map unsupported` | Path navigates into a `Map` |
| `wrong arity` | Function called with the wrong number of arguments |

> **XML-safe:** all operators are words (`and`, `or`, `not`, `lt`, `lte`, `gt`, `gte`) or
> XML-safe symbols (`==`, `!=`), so the `expr` attribute needs **no entity escaping** — write
> the expression verbatim. The old symbolic forms (`&&`, `||`, `!`, `<`, `>`, `<=`, `>=`) are
> removed; using one fails to parse and falls into the fail-safe above (`syntax error`).

See `openspec/changes/add-condition-dsl/design.md` for the full design decisions.

### Migrating from the previous flat form

The previous flat `<dynamic><attribute path="com.example.Car.brand"/></dynamic>` form is no
longer supported. Rewrite your `<dynamic>` section as one `<enrich class method>` block per
exit-point method, with paths prefixed by `$this` / `$argN` / `$return`. The `<static>`
section is unchanged.

# Used tooling
- opencode with gitnexus, ponytail and openspec
- gpt-5.6 luna
- glm 5.2
- M3
- Laguna S 2.1 Free

# Configuration Webserver

The agent embeds a lightweight HTTP server on `http://127.0.0.1:14317/` (loopback only, no external access) that lets operators view and update the declarative configuration at runtime without restarting the JVM.

## Usage

1. Open `http://127.0.0.1:14317/` in a browser.
2. The **Current Configuration** section shows the active XML.
3. Paste new XML into the textarea and click **Activate** to reload. The response shows rule and classloader counts. Invalid XML returns an error without changing the active state.
4. Click **Load Original** to fill the textarea with the decoded `OTEL_CUSTOM_AGENT_CONFIG` startup value, then **Activate** to revert to the default.

## Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/` | HTML configuration UI |
| GET | `/config/current` | Active XML (`text/xml`) or 404 |
| GET | `/config/original` | Decoded startup XML (`text/xml`), 404 if env var absent, 500 on invalid Base64 |
| POST | `/config` | Reload with raw XML body (`text/xml`); 200 on success, 400 on invalid XML |

## Limitations

- The server binds to `127.0.0.1` only; no authentication or TLS.
- Reload updates the rule index for classloaders registered at startup; it cannot instrument new root classes not matched at startup.
- If port `14317` is already in use, the server logs a warning and remains down; enrichment continues with the startup configuration.

# Tokens

## First attempt version 1
```
GPT-5.6 Luna
386,423 tokens
$23.67 spent
```
## Webserver added 
```
GLM 5.2
201,564 tokens
$14.39 spent
```

## Signoz
Signoz can be installed in kind kubernetes from signoz/./up.sh

## Bechmarking
Run scripts/./bench.sh to see the diff in % between config disabled and enabled (enabled captures 5 static and 10 dynamic properties)

## TODO
- done - benchmarking using k6
- done - avoid invoke, use LambdaMetafactory toepassen instead
- done - expression language (expression dsl)
- detect memory leaks
- security on hot reload config endpoint (stomp using topics)
- TTL on configuration. automatically expire the active configuration
