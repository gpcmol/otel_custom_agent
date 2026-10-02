> Status: implemented. This is the only supported configuration source; the former
> environment fallback and configuration webserver were removed afterward.

## Why

A file-backed configuration source enables GitOps workflows, including Kubernetes ConfigMaps,
without restarting the application or its autoinstrumentation.

## What Changes

- Use `OTEL_CUSTOM_AGENT_CONFIG_FILE` for the configuration file path.
- Keep `OTEL_CUSTOM_AGENT_CONFIG_RELOAD_INTERVAL`, defaulting to 5 seconds.
- Poll the configured file and load a new configuration when its contents change.
- Use the file as the only configuration source.
- Do not expose an embedded configuration webserver.
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
- The former Base64 environment configuration and embedded webserver are no longer supported.
