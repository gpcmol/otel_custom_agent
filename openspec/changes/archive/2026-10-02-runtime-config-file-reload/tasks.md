## 1. Configuration Source

- [x] 1.1 Add parsing for `OTEL_CUSTOM_AGENT_CONFIG_FILE` and `OTEL_CUSTOM_AGENT_CONFIG_RELOAD_INTERVAL`, including the 5-second default and validation of positive intervals.
- [x] 1.2 Add file-based configuration loading as the only configuration source.

## 2. Runtime Reload

- [x] 2.1 Implement a background poller that starts only when a configuration file path is set and stops during normal shutdown.
- [x] 2.2 Detect changed file content, reuse the existing parser and runtime publication path, and publish valid updates atomically without restarting the application or autoinstrumentation.
- [x] 2.3 Retain the last valid configuration when a file read or parse fails, log the failure, and retry on the next poll.
- [x] 2.4 Ensure unchanged content is not reparsed or republished.

## 3. Verification

- [x] 3.1 Add tests for file loading, absent files, default interval, and custom interval validation.
- [x] 3.2 Add tests for valid reloads, unchanged files, invalid or temporarily unreadable files, and shutdown cleanup.
- [x] 3.3 Add or update documentation and deployment examples for both file environment variables, including Kubernetes ConfigMap usage.
- [x] 3.4 Run the project test suite and a manual runtime reload smoke test without restarting the application or autoinstrumentation.
