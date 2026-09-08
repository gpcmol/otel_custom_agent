## Why

The current webserver can update the agent configuration at runtime, but that interface is intentionally insecure and suitable only for demos. A file-backed configuration source enables GitOps workflows, including Kubernetes ConfigMaps, without restarting the application or its autoinstrumentation.

## What Changes

- Add an optional environment variable for the path to a runtime configuration file.
- Add an optional polling interval environment variable, defaulting to 5 seconds.
- Poll the configured file and load a new configuration when its contents change.
- Give the file-backed configuration precedence over `OTEL_CUSTOM_AGENT_CONFIG`.
- Preserve `OTEL_CUSTOM_AGENT_CONFIG` as the fallback configuration source.
- Keep the existing webserver available for demo use.
- Do not require an application or autoinstrumentation restart when the file configuration changes.

## Capabilities

### New Capabilities

- `runtime-config-reload`: Load configuration changes from a local file or Kubernetes-mounted ConfigMap at runtime.

### Modified Capabilities

<!-- No existing capabilities are defined in openspec/specs/. -->

## Impact

- Configuration loading and agent runtime lifecycle.
- Environment variable documentation and deployment configuration.
- Filesystem polling and change detection.
- Kubernetes deployments using ConfigMap-mounted files.
- Existing webserver behavior remains available and unchanged.
