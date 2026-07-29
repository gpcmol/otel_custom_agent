## Context

The agent extension loads Base64-encoded XML configuration once at startup
through `RuntimeBridge.initialize()`, which calls `ConfigurationParser`,
builds an immutable `CompiledConfiguration`, and publishes a per-classloader
`RuntimeState` into a `WeakHashMap<ClassLoader, RuntimeState>`. The
instrumentation advice reads `RuntimeBridge.state(loader)` on each method
exit. There is no runtime reload path.

This change adds an embedded HTTP server that serves a minimal HTML page and
accepts raw XML, then republishes compiled state at runtime. The server must
run inside the agent extension classloader, use only JDK APIs, and never
disturb application threads or existing instrumentation.

The existing compilation pipeline (`ConfigurationParser` →
`CompiledConfiguration` → `RuleIndex` → `RuntimeState`) is reused unchanged.
The only new runtime behavior is a second activation trigger: an HTTP POST
that calls the same publish path with a freshly compiled configuration.

## Goals / Non-Goals

**Goals:**

- Serve a single HTML page with a textarea and Activate button on port
  `14317` using only `com.sun.net.httpserver.HttpServer`.
- Accept raw UTF-8 XML (not Base64) from the textarea and compile it through
  the existing parser into immutable state.
- Atomically republish `RuntimeState` for every application classloader
  already registered by startup initialization.
- Preserve the existing startup path, XML contract, immutability,
  classloader isolation, and fail-safe semantics.
- Keep invalid submitted XML from corrupting or disabling existing valid
  state; return a concise error to the operator.
- Add tests for HTTP serving, reload success, reload failure isolation, and
  concurrency on the reload path.

**Non-Goals:**

- Authentication, TLS, or access control on the webserver.
- Classloader discovery beyond those already registered at startup.
- Re-registration of `TypeInstrumentation` matchers after reload; the
  instrumentation matchers are determined at startup and unchanged. Reload
  only updates the rule index and compiled state that advice reads.
- Configuration merging, diffing, or version history.
- WebSocket, server-sent events, or live trace streaming.
- A Base64 input mode in the UI; the UI accepts plain XML only.
- Binding to a configurable port or address; the port is fixed at `14317`.

## Decisions

### JDK `java.net.ServerSocket` over `HttpServer` or a dependency

Use raw `java.net.ServerSocket` with minimal HTTP/1.1 parsing. The OTel
agent's `java-httpserver` instrumentation intercepts
`com.sun.net.httpserver.HttpServer` and wraps handlers, causing our custom
handlers to hang. A raw `ServerSocket` is not instrumented by the agent and
avoids this conflict entirely. The HTTP parsing is minimal (4 endpoints,
single-line request parsing) and requires no dependency.

Alternative: `com.sun.net.httpserver.HttpServer`. Rejected — the OTel agent
instruments it and the handler threads hang.

Alternative: add Jetty, Undertow, or Javalin. Rejected — the project forbids
new dependencies and the UI is a single page with one POST endpoint.

### Raw XML input, not Base64

The HTML textarea accepts plain UTF-8 XML. The server passes the raw XML
bytes to a new `ConfigurationParser.parseXml(String xml, ClassLoader)`
entry point that skips Base64 decoding and reuses the existing secure XML
parsing, validation, path compilation, and root-class resolution. The
existing `parse(String encoded, ClassLoader)` Base64 path remains unchanged
for startup.

Alternative: Base64-encode the XML in the browser before POSTing and reuse
`parse` unchanged. Rejected — it shifts encoding work to the browser,
complicates the HTML, and serves no purpose when the server already has the
raw text.

### Reload reuses startup publish, bounded to registered loaders

`RuntimeBridge.reload(String xml)` iterates the classloaders currently in
`STATES`, re-parses the XML with each loader, and atomically replaces that
loader's `RuntimeState`. Loaders not present at startup are not touched;
no new classloader discovery is added. The `WeakHashMap` keys govern the
scope.

On any per-loader parse failure, that loader's existing state is left
unchanged and the failure is recorded; one consolidated error response lists
which loaders failed and why, without exposing the XML payload beyond a
category and cause. Successful loaders still receive the new state even if
another loader fails.

Alternative: reload only a single "primary" classloader. Rejected — the
agent may host multiple application loaders and the operator does not know
which one to target.

Alternative: re-register instrumentation matchers for newly resolved root
classes. Rejected — the OTel agent `InstrumentationModule` matcher is
evaluated at class-prepare time and is not designed for runtime re-registration.
Reload updates the rule index that advice reads; the set of instrumented
types is fixed by startup configuration.

### HTML served as a static resource string

The HTML page is a single text-block string served by a `GET /` handler. It
contains a read-only "Current Configuration" section showing the active XML,
a `<textarea>` for new XML input, an Activate `<button>`, a "Load Original"
`<button>`, and a small inline `<script>`. On page load the script fetches
`GET /config/current` and renders it in the read-only section. The Activate
script POSTs the textarea content to `/config` as `text/xml`, renders the
response, and refreshes the current-config section. The Load Original script
fetches `GET /config/original` and fills the textarea with the returned XML.
No templating engine, no separate resource file, no build step.

The page MUST use minimal inline CSS in a single `<style>` block for a clean,
professional appearance: system font stack, comfortable spacing, subtle
borders, and a restrained color palette. No CSS framework, no external
stylesheets, no JavaScript beyond the single inline `<script>` block.

Alternative: load HTML from a resource file on the classpath. Rejected — it
adds a resource-loading path and a file to maintain for a single small page.
A text block is one constant, visible, and testable.

### `GET /config/current` returns the active XML

A `GET /config/current` handler returns the raw XML that produced the
currently active compiled configuration, as `text/xml; charset=utf-8` with
status `200`. Because the compiled `CompiledConfiguration` intentionally does
not retain the original path strings, the raw XML string MUST be captured
alongside the compiled state at publish time and stored in `RuntimeBridge`
as an `AtomicReference<String>`. This reference is updated atomically on
every startup publish and every reload publish, so it always reflects the
last successfully activated XML.

If no configuration has been activated (startup config absent and no reload
has occurred), the response MUST be `404` with a concise body indicating no
configuration is active. The handler MUST NOT re-serialize the compiled model;
it returns the exact XML string that was accepted.

The active XML is stored as a single `AtomicReference<String>` in
`RuntimeBridge`, set in both `publish` and `reload`. It is the only new
mutable field on the bridge and is bounded to one string, so it does not
introduce unbounded retention.

Alternative: re-serialize `CompiledConfiguration` to XML on request. Rejected
— the compiled model intentionally discards original path strings, and
re-serialization would produce a different representation than the operator
wrote.

Alternative: store the XML in each per-classloader `RuntimeState`. Rejected —
the active XML is identical across loaders (reload compiles the same XML for
all loaders), so a single bridge-level reference is sufficient and avoids
redundant per-loader copies.

### `GET /config/original` returns the decoded startup config

A `GET /config/original` handler reads `OTEL_CUSTOM_AGENT_CONFIG` once,
Base64-decodes it with strict UTF-8 validation (reusing the existing decode
logic), and returns the raw XML as `text/xml; charset=utf-8`. If the
variable is absent, the response is `404` with a concise body indicating no
startup configuration was set. If the value is not valid Base64 or not valid
UTF-8, the response is `500` with a concise error category; the existing
runtime state is untouched. The handler reads the environment value at
request time (not startup time) so it reflects the value the JVM was started
with; the JVM does not re-read the parent process environment, so this is the
value captured at agent start.

The Base64 decode is delegated to a small shared helper so the startup
`ConfigurationParser.decode` and this handler use identical decoding logic,
avoiding duplication.

Alternative: store the decoded startup XML at initialization and serve the
stored copy. Rejected — it duplicates the XML in memory and can drift from
the environment if the agent is re-initialized. Reading the environment
variable at request time is cheap and reflects the true startup source. The
env var is immutable for the JVM lifetime, so request-time reads are stable.

Alternative: return the compiled `CompiledConfiguration` re-serialized to
XML. Rejected — the compiled model intentionally does not retain the original
path strings, and re-serialization would produce a different representation
than the operator wrote. The operator wants the exact original XML.

### Server lifecycle tied to agent initialization

The server is started once during `RuntimeBridge` initialization (after
startup config is processed, regardless of whether startup config was
present, valid, or missing). It runs for the lifetime of the agent. The
`HttpServer` is stored as a static field and stopped on JVM shutdown via a
shutdown hook where the agent lifecycle supports it.

The server starts on a fixed port `14317`. If the port is already in use,
the server logs one warning and remains down; the startup config path and
instrumentation continue to work. The webserver is a convenience layer, not
a critical path — its failure must not affect enrichment.

## Risks / Trade-offs

- [Unauthenticated endpoint accepts arbitrary XML] → The server binds to
  `127.0.0.1` (loopback only) by default, limiting exposure to the local
  host. The XML parser already disables external entities and DTDs, so the
  existing secure-parsing guarantees apply to reload input. Document the
  loopback-only binding as the trust boundary.
- [Port 14317 already in use] → Log one warning, leave the server down, and
  continue with startup-only config. Reload is then unavailable until the
  port is freed and the JVM is restarted.
- [Reload races with concurrent method-exit enrichment] → `RuntimeState`
  is an immutable record and `STATES` is a `WeakHashMap` guarded by
  synchronization on `RuntimeBridge.class`. The advice reads
  `state(loader)` which returns the current immutable snapshot. A reload
  atomically swaps the reference; in-flight enrichments complete against
  the old snapshot. No lock is held during enrichment.
- [Invalid XML disables enrichment] → Reload failure leaves the existing
  state untouched per loader. Only successfully parsed configurations
  replace state.
- [New root classes in reloaded XML cannot be instrumented] → The
  instrumentation matchers are fixed at startup. Reload can change which
  rules apply to already-instrumented types and can add static attributes,
  but cannot instrument types that were not matched at startup. This is an
  explicit trade-off of not re-registering matchers. Document it in the UI
  response when the new config introduces root classes not present at
  startup.
- [`com.sun.net.httpserver` is JDK-internal] → It is a long-standing
  supported API in OpenJDK and Oracle JDK, documented in the
  `java.net.httpshare` module. It is not deprecated. The risk is low and
  the alternative (a dependency) is forbidden.

## Migration Plan

1. Add the webserver package and reload entry point without changing
   existing startup behavior. The server starts automatically; no
   environment variable is required.
2. Operators continue to set `OTEL_CUSTOM_AGENT_CONFIG` for the initial
   startup configuration as before.
3. To update configuration at runtime, open `http://127.0.0.1:14317/`,
   paste the new XML, and click Activate.
4. Roll back by restarting the JVM; the startup environment variable
   remains the source of truth for initial state. No persisted data or
   schema migration is required.

## Open Questions

- None. The port (`14317`), binding (`127.0.0.1`), input format (raw XML),
  and reload scope (registered loaders only) are all decided.
