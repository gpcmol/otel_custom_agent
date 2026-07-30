package org.otel.agent.config.parser;

/**
 * Thrown when XML configuration cannot be parsed, validated, or compiled.
 *
 * <p>Carries a human-readable message describing the failure (e.g. "invalid Base64",
 * "unknown XML element", "root class cannot be resolved") and optionally a cause for
 * underlying parse errors. Callers should present the message directly to operators
 * without unwrapping the cause, as the message is already at operator-facing granularity.
 */
public final class ConfigurationException extends Exception {
  public ConfigurationException(final String message) {
    super(message);
  }

  public ConfigurationException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
