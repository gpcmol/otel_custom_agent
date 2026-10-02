# Runtime configuration reload — current contract

`OTEL_CUSTOM_AGENT_CONFIG_FILE` is the only configuration source. The agent polls it every five
seconds by default, or at the positive number of seconds specified by
`OTEL_CUSTOM_AGENT_CONFIG_RELOAD_INTERVAL`.

At startup and on changed file content, XML is securely parsed and compiled for the relevant
application classloader. A valid result replaces the previous immutable runtime state atomically.
An unreadable, malformed, or classloader-incompatible update does not replace the last valid
state; the next poll retries it. If no valid configuration has ever been loaded, enrichment stays
disabled.

The reload mechanism does not merge configurations and does not use a webserver, TTL, or a second
configuration format.
