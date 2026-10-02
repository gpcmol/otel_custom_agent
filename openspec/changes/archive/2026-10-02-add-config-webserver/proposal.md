## Why

The agent currently loads its declarative enrichment configuration exactly
once at startup from `OTEL_CUSTOM_AGENT_CONFIG`. Changing rules requires an
environment-variable update and a full application restart, which is costly
on long-running JVMs and disrupts live traffic. An embedded web UI lets
operators paste the XML contract and activate it at runtime, turning config
updates into a seconds-level operation without restarting the JVM or losing
existing instrumentation.

## What Changes

- Add an embedded pure-Java HTTP server on port `14317` using only the JDK
  `com.sun.net.httpserver.HttpServer` API; no web framework or new dependency.
- Serve a single HTML page with a read-only "Current Configuration" section
  showing the active XML, a textarea for new XML input, an "Activate" button
  that posts the raw XML to the server, and a "Load Original" button that
  fetches the startup `OTEL_CUSTOM_AGENT_CONFIG` value, Base64-decodes it,
  and fills the textarea with the original XML so operators can revert to
  the default configuration. The page uses minimal inline CSS for a clean,
  professional appearance with no external resources.
- Add runtime configuration reload: parse the submitted XML through the
  existing `ConfigurationParser` pipeline and atomically republish
  `RuntimeState` for known application classloaders.
- Extend `RuntimeBridge` with a reload entry point and an active-XML
  reference that tracks the last successfully activated XML, preserving the
  per-classloader isolation, immutability, and fail-safe semantics already
  defined by the existing capability.
- The server accepts plain UTF-8 XML (not Base64); the existing Base64
  startup path remains unchanged.
- Invalid submitted XML produces a concise error response without disabling
  existing valid runtime state.
- Add focused tests for the HTTP endpoints, HTML response, reload success,
  reload failure isolation, and concurrency on the reload path.

## Capabilities

### New Capabilities

- `config-webserver`: Embedded pure-Java web UI and runtime configuration
  reload endpoint on port 14317.

### Modified Capabilities

- `trace-attribute-enrichment`: Adds runtime reload of compiled
  configuration state in addition to the existing startup-only load. The
  XML contract, compilation, immutability, classloader isolation, and
  fail-safe semantics remain unchanged; only the activation timing expands
  from startup-only to startup-plus-runtime.

## Impact

- Adds a new `webserver` package under `org.otel.agent` containing the HTTP
  server, HTML resource, and reload handler.
- Extends `RuntimeBridge` with a `reload` method that republishes state for
  registered application classloaders.
- Introduces no new dependencies; uses only JDK `com.sun.net.httpserver`,
  `java.net`, and `java.io` APIs.
- Runtime reload is intentionally bounded to classloaders already registered
  by startup initialization; no classloader discovery or instrumentation
  re-registration is added.
- The webserver lifecycle is tied to the agent extension lifecycle: started
  once during initialization, stopped on agent shutdown where supported.
> Status: superseded. The embedded configuration webserver was removed in favor
> of file-only GitOps configuration.
