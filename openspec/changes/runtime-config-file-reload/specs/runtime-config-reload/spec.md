## ADDED Requirements

### Requirement: File-backed runtime configuration
The agent MUST support an optional `OTEL_CUSTOM_AGENT_CONFIG_FILE` environment variable containing a path to a configuration file. When configured, the file configuration MUST take precedence over `OTEL_CUSTOM_AGENT_CONFIG`.

#### Scenario: File configuration is selected at startup
- **WHEN** `OTEL_CUSTOM_AGENT_CONFIG_FILE` points to a readable valid configuration file
- **THEN** the agent MUST initialize from that file and MUST NOT initialize from `OTEL_CUSTOM_AGENT_CONFIG`

#### Scenario: Environment configuration remains the fallback
- **WHEN** no usable file configuration is configured and `OTEL_CUSTOM_AGENT_CONFIG` contains a valid configuration
- **THEN** the agent MUST initialize from `OTEL_CUSTOM_AGENT_CONFIG`

#### Scenario: Neither source is available
- **WHEN** no usable file configuration exists and `OTEL_CUSTOM_AGENT_CONFIG` is absent
- **THEN** the agent MUST preserve the existing no-configuration startup behavior

### Requirement: Polling interval
The agent MUST poll the configured file for changes using the `OTEL_CUSTOM_AGENT_CONFIG_RELOAD_INTERVAL` environment variable as an interval in seconds, defaulting to 5 seconds when the variable is absent or blank.

#### Scenario: Default interval
- **WHEN** a file path is configured and `OTEL_CUSTOM_AGENT_CONFIG_RELOAD_INTERVAL` is absent or blank
- **THEN** the agent MUST check for file changes every 5 seconds

#### Scenario: Custom interval
- **WHEN** a valid positive interval is configured
- **THEN** the agent MUST use that interval between file checks

### Requirement: Runtime reload
The agent MUST load and atomically publish a new valid configuration when the file content changes, without restarting the application or autoinstrumentation.

#### Scenario: Valid file change
- **WHEN** the configured file changes to a valid configuration
- **THEN** the new configuration MUST become active after the next poll

#### Scenario: Unchanged file
- **WHEN** a poll finds the same file content as the last processed content
- **THEN** the agent MUST NOT reparse or republish the configuration

#### Scenario: Invalid or temporarily unreadable update
- **WHEN** a changed file cannot be read or parsed as a valid configuration
- **THEN** the agent MUST retain the last valid active configuration and retry on a later poll

### Requirement: Existing webserver compatibility
The existing configuration webserver MUST remain available and MUST continue to update runtime configuration through its current behavior.

#### Scenario: Webserver update after file mode is enabled
- **WHEN** the webserver receives a valid runtime configuration update
- **THEN** the update MUST be applied through the existing runtime path without requiring a process restart
