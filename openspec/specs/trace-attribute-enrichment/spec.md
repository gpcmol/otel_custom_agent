# Trace attribute enrichment — current contract

This is the current contract implemented by the agent. Older specifications under
`openspec/changes/` describe historical changes and are not additional active requirements.

## Configuration

The agent reads one XML document from the file named by
`OTEL_CUSTOM_AGENT_CONFIG_FILE`. The root is `<configuration>` and may contain `<static>` and
`<dynamic>` sections. Unknown elements, attributes, namespaces, duplicate keys, blank required
values, and unsupported legacy flat dynamic attributes are rejected.

Static attributes use:

```xml
<static>
  <attribute key="domain" value="cars"/>
</static>
```

Dynamic attributes are grouped by an exit point:

```xml
<dynamic>
  <enrich class="com.example.Garage" method="park"
          expr="$arg0.brand == 'BMW' and ilike($arg0.model, 'x%')">
    <attribute key="brand" path="$arg0.brand"/>
    <attribute key="accepted" path="$return.accepted"/>
  </enrich>
</dynamic>
```

There may be only one `<enrich>` block for a `(class, method)` pair. The class must be loadable
through the application classloader and the method name must exist; overload signatures are not
selectable.

## Compiled model and runtime

- Configuration is compiled once at startup or reload into immutable `CompiledConfiguration`,
  `ExitPoint`, and `ExitRule` records.
- A path must start with `$this`, `$argN` (`0..127`), or `$return`; the remainder is compiled into
  property/index segments. Original path strings are not used on the hot path.
- `ExitPointIndex` resolves `(runtime class, method name)` using `isAssignableFrom` matching and a
  bounded FIFO cache of 1000 entries. Negative lookups are cached as an empty list.
- `$this` is the receiver, `$argN` is an argument, and `$return` is the returned value. Missing
  arguments and returns from void methods resolve to `null` and are skipped.
- Properties are resolved getter-first (`getX`, then boolean `isX`, then public field). Maps,
  static properties, and arbitrary method calls are unsupported.
- A valid current span is enriched once per configured exit-point invocation. Without one, one
  fallback `INTERNAL` span is created, enriched, and ended.
- Enrichment failures never change application behavior. Void and non-void Byte Buddy advice are
  separate because `@Advice.Return Object` is invalid for void methods.
- Static rules are written once per enrichment event; dynamic rules are resolved once per rule.

## Instrumentation

The OTel `InstrumentationModule` matches configured root types and subclasses. Because Byte Buddy
method matchers are installed before configuration is loaded, advice is installed on eligible
instance methods of matching types and the runtime index filters non-exit-point methods.
Constructors, static, abstract, native, bridge, and synthetic methods are excluded.

## Runtime reload

The file is polled every five seconds by default; a positive value of
`OTEL_CUSTOM_AGENT_CONFIG_RELOAD_INTERVAL` changes the interval. Valid changed XML is atomically
published. Unreadable or invalid changes retain the last valid state and are retried later.

There is no embedded configuration webserver, Base64 environment configuration, or TTL.
