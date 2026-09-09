# Introduction

The OpenTelemetry Custom Agent gives development teams an operational switch for debugging
applications in production without restarting them. A team can place a declarative XML
configuration file in a mounted volume for a specific application. The agent polls that file,
loads valid changes, and activates them at runtime.

This makes production debugging targeted and temporary in practice: enable the configuration for
one application, collect the traces that matter, then remove or update the file when debugging is
finished.

# Purpose

The agent enriches spans with application-specific attributes defined in XML. Those attributes can
be used by the OpenTelemetry tail sampler to keep selected traces. For example, a tail-sampling
configuration can include:

```yaml
- name: debug
  type: string_attribute
  string_attribute:
    key: debug
    values: ["true"]
```

When the agent configuration sets `debug=true` on a span, the trace matches this policy and is
captured. Other configured attributes can be used to filter and investigate traces in Grafana
Tempo.

The main benefit is a per-application debugging switch: teams can increase observability where it
is needed without capturing and storing large volumes of traces that are not being investigated.

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

The agent reads its configuration from the XML file named by
`OTEL_CUSTOM_AGENT_CONFIG_FILE`. `OTEL_CUSTOM_AGENT_CONFIG_RELOAD_INTERVAL` controls how often
the file is checked and defaults to 5 seconds. There is no environment-variable XML fallback,
embedded configuration webserver, or configuration TTL.

## Exit points

An **exit point** is an application method where the agent enriches the current span when the
method returns. In the example below, `Garage.park(Car car)` is the exit point:

```java
public final class Garage {
    public Result park(Car car) {
        // The agent enriches the current span when this method exits.
        // The method argument is available in the XML configuration as $arg0.
        boolean accepted = parkedCars.add(car);
        return new Result(accepted, accepted ? "accepted" : "rejected");
    }
}
```

The XML configuration identifies the exit point with `class="com.example.Garage"` and
`method="park"`. Paths beginning with `$arg0` read properties from the first method argument,
in this case the `Car` object. `$this` refers to the `Garage` instance and `$return` refers to the
method's return value. Because `park` returns a `Result` object, properties of that object can be
captured with paths such as `path="$return.accepted"` and `path="$return.status"`.

Declare one **exit point** per span-producing method. Each `<enrich class method>` block bundles
the attribute rules that apply when that method exits. Paths start from a configurable root:
`$this` (receiver), `$argN` (Nth parameter, 0..127), or `$return` (return value). The rest of
each path uses the existing property/index grammar (e.g. `passengers[1].name`).

```xml
<configuration>
    <static>
        <attribute key="debug" value="true"/>
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
            <attribute key="accepted" path="$return.accepted"/>
            <attribute key="status" path="$return.status"/>
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
- gpt-5.6 luna, glm 5.2, M3, Laguna S 2.1 Free

## GitOps Configuration Reload

For deployments that need configuration updates without restarting the JVM or autoinstrumentation,
set `OTEL_CUSTOM_AGENT_CONFIG_FILE` to a local XML file. The agent checks the file every 5 seconds
by default. Set `OTEL_CUSTOM_AGENT_CONFIG_RELOAD_INTERVAL` to a positive number of seconds to
change the interval.

The file is the only configuration source. If it is missing, unreadable, or invalid, the last valid
runtime configuration remains active and the agent retries on the next poll. If no valid file has
been loaded at startup, enrichment remains disabled until one becomes available.

The file can be a Kubernetes ConfigMap mount, for example:

```yaml
env:
  - name: OTEL_CUSTOM_AGENT_CONFIG_FILE
    value: /etc/otel-agent/config.xml
  - name: OTEL_CUSTOM_AGENT_CONFIG_RELOAD_INTERVAL
    value: "5"
volumeMounts:
  - name: agent-config
    mountPath: /etc/otel-agent
volumes:
  - name: agent-config
    configMap:
      name: otel-agent-config
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
- done telemetry docker image
- done config from file
