## ADDED Requirements

### Requirement: The webserver SHALL use only JDK APIs and no new dependencies

The webserver MUST be implemented using standard `java.net` / `java.io` APIs
(raw `ServerSocket` to avoid OTel agent `java-httpserver` instrumentation
interference). It MUST NOT add a web framework, HTTP library, template
engine, or any new project dependency. It MUST be pure Java and MUST compile
with the existing project build.

#### Scenario: No new dependencies are introduced

- **WHEN** the webserver is built
- **THEN** it MUST compile using only JDK standard APIs and existing project
  dependencies
- **AND** no web framework or HTTP library dependency MUST be added

### Requirement: The webserver SHALL listen on port 14317 bound to loopback

The server MUST listen on port `14317` bound to the loopback address
`127.0.0.1`. It MUST NOT bind to all interfaces by default. If the port is
already in use, the server MUST log one warning and remain down without
affecting startup configuration or instrumentation. The server MUST start
once during agent initialization and run for the agent lifetime.

#### Scenario: Server starts on port 14317

- **WHEN** the agent initializes
- **THEN** an HTTP server MUST listen on `127.0.0.1:14317`
- **AND** it MUST NOT bind to external interfaces

#### Scenario: Port already in use does not break the agent

- **WHEN** port `14317` is already in use at agent startup
- **THEN** the server MUST log one warning
- **AND** it MUST remain down
- **AND** startup configuration loading and instrumentation MUST continue
  to work normally

### Requirement: The webserver SHALL serve an HTML configuration UI

A `GET /` request MUST return an HTML page with `Content-Type: text/html;
charset=utf-8`. The page MUST contain a read-only "Current Configuration"
section that displays the active XML, a `<textarea>` for XML configuration
input, an Activate `<button>`, and a "Load Original" `<button>`. The page
MUST contain an inline `<script>` that on page load fetches `GET
/config/current` and renders it in the read-only section, POSTs the textarea
content to `/config` as `text/xml` when the Activate button is clicked,
refreshes the current-config section after activation, and fetches `GET
/config/original` when the Load Original button is clicked and fills the
textarea with the returned XML. The HTML MUST be self-contained with no
external resources, stylesheets, or scripts. The page MUST use minimal inline
CSS in a single `<style>` block for a clean, professional appearance using a
system font stack, comfortable spacing, and a restrained color palette; no
CSS framework or external stylesheet MUST be added.

#### Scenario: GET root returns the HTML UI

- **WHEN** a client requests `GET /`
- **THEN** the response status MUST be `200`
- **AND** the body MUST be HTML containing a read-only current-configuration
  section, a textarea, an Activate button, and a Load Original button
- **AND** the `Content-Type` MUST be `text/html; charset=utf-8`

#### Scenario: HTML page is self-contained and professional

- **WHEN** the HTML page is rendered
- **THEN** it MUST NOT reference any external URL, stylesheet, or script
- **AND** it MUST function without network access
- **AND** it MUST use only a single inline `<style>` block and a single
  inline `<script>` block

### Requirement: The original-config endpoint SHALL return decoded startup XML

A `GET /config/original` request MUST read the `OTEL_CUSTOM_AGENT_CONFIG`
environment variable, Base64-decode it with strict UTF-8 validation reusing
the existing decode logic, and return the raw XML as
`Content-Type: text/xml; charset=utf-8` with status `200`. If the variable
is absent, the response MUST be `404` with a concise body indicating no
startup configuration was set. If the value is not valid Base64 or not valid
UTF-8, the response MUST be `500` with a concise error category and the
existing runtime state MUST remain unchanged. The response MUST contain only
the decoded XML; no status metadata or wrapper MUST be added.

#### Scenario: Original config is returned as XML

- **WHEN** a client requests `GET /config/original` and
  `OTEL_CUSTOM_AGENT_CONFIG` is set to valid Base64-encoded UTF-8 XML
- **THEN** the response status MUST be `200`
- **AND** the `Content-Type` MUST be `text/xml; charset=utf-8`
- **AND** the body MUST be the decoded XML identical to the original startup
  configuration

#### Scenario: Missing startup config returns 404

- **WHEN** a client requests `GET /config/original` and
  `OTEL_CUSTOM_AGENT_CONFIG` is absent
- **THEN** the response status MUST be `404`
- **AND** the body MUST indicate no startup configuration was set

#### Scenario: Invalid Base64 startup config returns 500

- **WHEN** a client requests `GET /config/original` and
  `OTEL_CUSTOM_AGENT_CONFIG` is not valid Base64 or not valid UTF-8
- **THEN** the response status MUST be `500`
- **AND** the body MUST contain a concise error category
- **AND** the existing runtime state MUST remain unchanged

### Requirement: The current-config endpoint SHALL return the active XML

A `GET /config/current` request MUST return the raw XML that produced the
currently active compiled configuration as `Content-Type: text/xml;
charset=utf-8` with status `200`. The active XML MUST be captured at publish
time and stored as a single atomic reference in `RuntimeBridge`, updated on
both startup publish and reload publish. The handler MUST NOT re-serialize
the compiled model; it MUST return the exact XML string that was accepted.
If no configuration has been activated, the response MUST be `404` with a
concise body indicating no configuration is active.

#### Scenario: Active config is returned as XML

- **WHEN** a client requests `GET /config/current` after a configuration has
  been activated (startup or reload)
- **THEN** the response status MUST be `200`
- **AND** the `Content-Type` MUST be `text/xml; charset=utf-8`
- **AND** the body MUST be the exact XML that was last accepted

#### Scenario: No active config returns 404

- **WHEN** a client requests `GET /config/current` and no configuration has
  been activated
- **THEN** the response status MUST be `404`
- **AND** the body MUST indicate no configuration is active

#### Scenario: Reload updates the active config

- **WHEN** a reload request succeeds and a subsequent `GET /config/current`
  request is made
- **THEN** the response body MUST contain the reloaded XML, not the previous
  startup XML

### Requirement: The reload endpoint SHALL accept raw XML and republish state

A `POST /config` request with `Content-Type: text/xml` MUST accept raw UTF-8
XML in the request body. The server MUST compile the XML through the existing
`ConfigurationParser` pipeline and atomically republish `RuntimeState` for
every application classloader already registered by startup initialization.
On success the response MUST be `200` with a concise body containing the new
static rule count, dynamic rule count, and number of updated classloaders.
The response MUST NOT echo the submitted XML.

#### Scenario: Valid XML is activated

- **WHEN** a `POST /config` request contains valid XML conforming to the
  documented contract
- **THEN** the response status MUST be `200`
- **AND** the compiled configuration MUST replace the existing runtime state
  for every registered classloader
- **AND** the response body MUST contain rule and classloader counts
- **AND** the response body MUST NOT contain the submitted XML

#### Scenario: Invalid XML does not disable existing state

- **WHEN** a `POST /config` request contains invalid or malformed XML
- **THEN** the response status MUST be `400`
- **AND** the response body MUST contain a concise error category and cause
- **AND** the existing runtime state for each registered classloader MUST
  remain unchanged
- **AND** the response body MUST NOT contain the submitted XML

### Requirement: Reload SHALL preserve immutability and classloader isolation

The reloaded `RuntimeState` MUST be immutable and published atomically per
classloader. The reload MUST only affect classloaders already present in the
runtime state map at the time of the request. Loaders not registered at
startup MUST NOT be discovered or affected. A per-loader parse failure MUST
leave that loader's existing state unchanged while successful loaders still
receive the new state.

#### Scenario: Partial reload failure leaves successful loaders updated

- **WHEN** a reload request compiles successfully for one registered
  classloader and fails for another
- **THEN** the successful loader MUST receive the new runtime state
- **AND** the failed loader MUST retain its previous runtime state
- **AND** the response MUST indicate which loaders failed without exposing
  the XML payload

#### Scenario: Reload does not discover new classloaders

- **WHEN** a classloader was not registered during startup initialization
- **THEN** a reload request MUST NOT create state for that classloader
- **AND** that classloader MUST NOT be affected by the reload

### Requirement: Reload SHALL be concurrent-safe with method-exit enrichment

The reload path MUST NOT hold a lock during enrichment. `RuntimeState` is an
immutable snapshot; the reload MUST atomically swap the per-loader reference.
In-flight enrichments MUST complete against the snapshot they obtained. The
reload path MUST be safe under concurrent reload requests and concurrent
method-exit enrichment on multiple threads.

#### Scenario: Concurrent reload and enrichment are safe

- **WHEN** a reload request runs concurrently with method-exit enrichment
  on multiple threads
- **THEN** each enrichment MUST complete against a consistent immutable
  snapshot
- **AND** no corrupted or partially-published state MUST be observed
- **AND** the reload MUST NOT synchronize on the span, receiver, or a global
  lock during enrichment

### Requirement: The webserver SHALL NOT affect application behavior on failure

Webserver startup failure, port conflict, handler exceptions, or reload
errors MUST NOT propagate into application methods or affect existing
instrumentation. The webserver is a convenience layer; its failure MUST be
silent or logged at warning level and MUST NOT disable enrichment that was
already configured at startup.

#### Scenario: Webserver failure does not break enrichment

- **WHEN** the webserver fails to start or a handler throws an exception
- **THEN** existing startup-configured enrichment MUST continue to work
- **AND** no exception MUST escape into application code

### Requirement: The webserver package SHALL keep responsibilities separate

The webserver MUST reside in a dedicated `org.otel.agent.webserver` package.
It MUST NOT own XML parsing, path compilation, rule indexing, or telemetry.
It MUST delegate compilation to `ConfigurationParser` and publication to
`RuntimeBridge`. The HTML content MUST be a constant in the webserver
package, not in the configuration or runtime packages.

#### Scenario: Webserver delegates to existing compilation

- **WHEN** the reload handler processes a request
- **THEN** it MUST delegate XML compilation to `ConfigurationParser`
- **AND** it MUST delegate state publication to `RuntimeBridge`
- **AND** it MUST NOT duplicate parsing, validation, or rule-index logic

### Requirement: The webserver SHALL provide complete verification

Tests MUST cover the `GET /` HTML response, `GET /config/current` success,
no-active-config, and reload-updates-current responses, `GET /config/original`
success, missing-config, and invalid-Base64 responses, `POST /config` success
and failure, reload state replacement, partial reload failure isolation,
concurrency on the reload path, loopback binding, port-conflict handling,
and the requirement that webserver failure does not affect enrichment.
Tests MUST be runnable without Docker.

#### Scenario: Required behavior is executable

- **WHEN** the standard project verification command runs
- **THEN** tests MUST verify HTTP serving, current-config retrieval,
  original-config retrieval, reload success, reload failure isolation,
  concurrency safety, and application-safety requirements
- **AND** all tests MUST pass without Docker
> Status: superseded. This capability is intentionally removed and is not a
> current agent requirement.
