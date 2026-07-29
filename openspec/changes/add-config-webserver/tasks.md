## 1. Raw-XML Parser Entry Point

- [x] 1.1 Add `ConfigurationParser.parseXml(String xml, ClassLoader loader)` that accepts raw UTF-8 XML, skips Base64 decoding, and reuses the existing `parseXml(byte[])`, `validateElement`, `parseStatic`, `parseDynamic`, and path-compilation logic.
- [x] 1.2 Extract the existing Base64+UTF-8 decode logic into a shared static helper (e.g. `ConfigurationParser.decode(String)`) reusable by both the Base64 `parse` path and the `GET /config/original` handler, without changing existing behavior.
- [x] 1.3 Verify `parseXml` applies the same secure XML features, structural validation, key validation, path grammar, and root-class resolution as the Base64 `parse` path.
- [x] 1.4 Add unit tests for `parseXml` covering valid XML, missing sections, unknown elements/attributes, duplicate keys, invalid paths, unresolved roots, secure XML (external entity rejection), and immutable compiled output.

## 2. RuntimeBridge Reload Entry Point

- [x] 2.1 Add `RuntimeBridge.reload(String xml)` that iterates the classloaders currently in `STATES`, calls `parseXml` with each loader, and atomically replaces that loader's `RuntimeState` via `publish` on success.
- [x] 2.2 Add an `AtomicReference<String>` field to `RuntimeBridge` that stores the active XML string; update it in both `publish` and `reload` so it always reflects the last successfully accepted XML.
- [x] 2.3 Add `RuntimeBridge.activeXml()` returning the current active XML string (or `null` when none active) for the `GET /config/current` handler.
- [x] 2.4 On per-loader parse failure, leave that loader's existing state unchanged, record the failure, and continue processing remaining loaders.
- [x] 2.5 Return a reload result (success count, updated classloader count, failures with concise category/cause) without exposing the submitted XML.
- [x] 2.6 Update `ROOT_NAMES` atomically after a successful reload so instrumentation matchers see the new root-class-name set.
- [x] 2.7 Add unit tests for reload success, partial failure isolation (one loader succeeds, one fails), all-failures leave existing state, no discovery of unregistered loaders, atomic `ROOT_NAMES` update, and active-XML reference updated on both startup publish and reload.

## 3. Webserver Package and HTTP Server

- [x] 3.1 Create `org.otel.agent.webserver` package with a `ConfigWebserver` class using raw `java.net.ServerSocket` bound to `127.0.0.1` port `14317` (avoids OTel agent `java-httpserver` instrumentation interference).
- [x] 3.2 Implement `GET /` handler that returns the HTML page with `Content-Type: text/html; charset=utf-8`, a read-only "Current Configuration" section, a textarea, an Activate button, a "Load Original" button, a single inline `<style>` block with minimal professional CSS (system font stack, spacing, restrained palette), and an inline script that on load fetches `GET /config/current` into the read-only section, POSTs to `/config` on Activate, refreshes current-config after activation, and fetches `GET /config/original` on Load Original into the textarea.
- [x] 3.3 Define the HTML page as a single text-block constant in the webserver package; verify it is self-contained with no external URLs, stylesheets, or scripts, only one `<style>` block and one `<script>` block.
- [x] 3.4 Implement `POST /config` handler that reads the raw XML body, calls `RuntimeBridge.reload`, returns `200` with rule/classloader counts on success, and `400` with concise error category/cause on failure; never echo the submitted XML.
- [x] 3.5 Implement `GET /config/original` handler that reads `OTEL_CUSTOM_AGENT_CONFIG` at request time, decodes it via the shared decode helper, and returns the raw XML as `text/xml; charset=utf-8` with status `200`; return `404` when the variable is absent and `500` with a concise error category on invalid Base64/UTF-8, leaving runtime state unchanged.
- [x] 3.6 Implement `GET /config/current` handler that returns `RuntimeBridge.activeXml()` as `text/xml; charset=utf-8` with status `200`, or `404` with a concise body when no configuration is active.
- [x] 3.7 Handle method and path validation: reject non-POST requests to `/config` with `405`, non-GET requests to `/config/original` and `/config/current` with `405`, and unknown paths with `404`.
- [x] 3.8 Start the server once during `RuntimeBridge` initialization (after startup config processing), store the `ServerSocket` as a static field.
- [x] 3.9 On port-in-use or server start failure, log one warning and leave the server down without affecting startup config or instrumentation.

## 4. Webserver Tests

- [x] 4.1 Add `GET /` test: assert status `200`, `Content-Type: text/html; charset=utf-8`, body contains read-only current-config section, textarea, Activate button, and Load Original button, a single `<style>` block, a single `<script>` block, and no external resource references.
- [x] 4.2 Add `GET /config/current` success test: after startup publish, assert status `200`, `Content-Type: text/xml; charset=utf-8`, body equals the active XML.
- [x] 4.3 Add `GET /config/current` no-active-config test: before any publish, assert status `404` and concise body.
- [x] 4.4 Add `GET /config/current` reload-updates test: after a reload, assert the current-config response contains the reloaded XML, not the startup XML.
- [x] 4.5 Add `GET /config/original` success test: set `OTEL_CUSTOM_AGENT_CONFIG` to valid Base64 XML, assert status `200`, `Content-Type: text/xml; charset=utf-8`, body equals the decoded XML.
- [x] 4.6 Add `GET /config/original` missing-config test: assert status `404` and concise body when the variable is absent.
- [x] 4.7 Add `GET /config/original` invalid-Base64 test: set the variable to invalid Base64, assert status `500`, concise error category, and runtime state unchanged.
- [x] 4.8 Add `POST /config` success test: submit valid XML, assert status `200`, body contains rule/classloader counts, and verify `RuntimeBridge.state` reflects the new compiled configuration.
- [x] 4.9 Add `POST /config` failure test: submit invalid XML, assert status `400`, body contains error category/cause, and verify existing runtime state is unchanged.
- [x] 4.10 Add partial-reload-failure test: two registered classloaders where one parse fails; assert successful loader updated, failed loader unchanged, response indicates the failure without XML payload.
- [x] 4.11 Add concurrency test: concurrent reload requests and concurrent `state(loader)` reads; assert no corrupted state and each read returns a consistent immutable snapshot.
- [x] 4.12 Add port-conflict test: start a server on `14317`, then start `ConfigWebserver` and assert it logs a warning and remains down without throwing.
- [x] 4.13 Add application-safety test: webserver handler exception or server failure does not affect existing enrichment state or escape into application code.
- [x] 4.14 Ensure all webserver tests are runnable without Docker.

## 5. Integration and Verification

- [x] 5.1 Wire `ConfigWebserver.start()` into `RuntimeBridge.initialize` so the server starts automatically after startup config is processed, regardless of whether startup config was present, valid, or missing.
- [x] 5.2 Run SonarLint/static-analysis and SpotBugs checks on the new code; fix all findings, verify no production `var`, explicit final locals/parameters, try-with-resources for `AutoCloseable` resources, and text blocks for multiline HTML.
- [x] 5.3 Run the complete Gradle quality/test command and verify all existing tests still pass alongside the new webserver and reload tests.
- [x] 5.4 Add a manual smoke-test note documenting `http://127.0.0.1:14317/` access, current-config display, XML paste, Activate click, Load Original click to revert to the `OTEL_CUSTOM_AGENT_CONFIG` default, and expected response formats.
