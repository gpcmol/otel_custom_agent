## Why

The repository has a complete baseline contract for enriching OpenTelemetry
spans from configured application objects, but no implementation change exists
to deliver it. This change turns the baseline into a working Java agent
extension while preserving application behavior and avoiding new dependencies.

## What Changes

- Add startup configuration loading from `OTEL_CUSTOM_AGENT_CONFIG`.
- Decode strict Base64/UTF-8 input and securely parse and validate XML.
- Compile dynamic paths into immutable rules and build class-based indexes.
- Add cached getter/field resolution for nested, indexed, and collection values.
- Add Byte Buddy/OpenTelemetry agent instrumentation and span enrichment.
- Add current-span enrichment and ended `INTERNAL` fallback spans.
- Isolate configuration and runtime failures from application code.
- Add focused tests for configuration, resolution, instrumentation, classloaders,
  caching, concurrency, and telemetry behavior.

## Capabilities

### New Capabilities

- `trace-attribute-enrichment`: Configured static and runtime-derived
  OpenTelemetry span attribute enrichment.

### Modified Capabilities

<!-- No existing requirement-level capability changes. -->

## Impact

- Adds Java agent extension configuration, runtime, resolver, telemetry, and
  instrumentation code.
- Adds tests and executable verification through the existing project build.
- Uses only Java standard APIs and dependencies already supplied by the agent
  build; no new XML, reflection, bytecode, or configuration framework is
  introduced.
- Runtime behavior is intentionally fail-safe: invalid startup configuration
  disables this extension, and enrichment failures do not alter application
  exceptions or return values.
> Status: superseded. Startup configuration is now loaded from the XML file
> specified by `OTEL_CUSTOM_AGENT_CONFIG_FILE`; the former Base64 environment
> variable is no longer supported.
