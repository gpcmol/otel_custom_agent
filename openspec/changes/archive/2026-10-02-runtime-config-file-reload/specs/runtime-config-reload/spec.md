## ADDED Requirements

### Requirement: File-backed runtime configuration
The agent MUST use `OTEL_CUSTOM_AGENT_CONFIG_FILE` as its only configuration source when the
variable contains a path to an XML configuration file.

#### Scenario: File configuration is selected at startup
- **WHEN** `OTEL_CUSTOM_AGENT_CONFIG_FILE` points to a readable valid configuration file
- **THEN** the agent MUST initialize from that file

#### Scenario: File configuration is unavailable
- **WHEN** no usable file configuration exists
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
