> Status: implemented. File configuration is authoritative; the former environment fallback
> and embedded webserver are removed.

## Context

The agent initializes configuration from an XML file that can be changed by GitOps, including a
file mounted from a Kubernetes ConfigMap, without restarting the process or autoinstrumentation.

## Goals / Non-Goals

**Goals:**

- Support file-backed runtime configuration through `OTEL_CUSTOM_AGENT_CONFIG_FILE`.
- Detect changes using a configurable polling interval, defaulting to 5 seconds.
- Apply valid file configurations to the existing runtime state.
- Treat the file as the only configuration source.

**Non-Goals:**

- Adding a new configuration format.
- Watching arbitrary directories or requiring a filesystem notification dependency.
- Restarting the application or Java agent when configuration changes.

## Decisions

- Use `OTEL_CUSTOM_AGENT_CONFIG_FILE` for the file path and `OTEL_CUSTOM_AGENT_CONFIG_RELOAD_INTERVAL` for the polling interval. The interval is expressed in seconds and defaults to `5` when absent or blank.
- Poll from a background runtime component rather than using a new dependency or platform-specific filesystem watcher. Polling works for ordinary files and Kubernetes ConfigMap projection updates with the same behavior.
- Read the file and compare its configuration content on each poll. This handles ConfigMap implementations that replace a symlink or inode rather than modifying a file in place, and avoids reapplying unchanged content.
- Resolve the initial source from the configured file. A missing or invalid file leaves the runtime disabled until a valid file becomes available.
- Reuse the existing configuration parser and runtime-state publication path. A valid update replaces the active configuration atomically; an invalid or unreadable update leaves the last valid configuration active and is retried on the next poll.
- Start the poller only when a file path is configured, and stop it during normal runtime shutdown.

## Risks / Trade-offs

- [Polling adds a small periodic filesystem and parsing cost] -> Poll only when file mode is enabled, use the configured interval, and skip unchanged content.
- [A file can be observed while it is being written] -> Treat parse failures as transient, retain the last valid state, and retry on the next interval; deployments should use atomic replacement, as Kubernetes ConfigMaps do.
- [A malformed GitOps change remains active as the previous configuration] -> Log the failure clearly and never publish a partial or invalid configuration.

## Migration Plan

Deployments must mount a configuration file and set `OTEL_CUSTOM_AGENT_CONFIG_FILE`; optionally set
`OTEL_CUSTOM_AGENT_CONFIG_RELOAD_INTERVAL`. Removing the file path disables enrichment.

## Open Questions

- Confirm the final environment variable names with the implementation's existing naming conventions before coding.
