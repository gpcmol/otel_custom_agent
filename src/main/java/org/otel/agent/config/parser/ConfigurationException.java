package org.otel.agent.config.parser;

public final class ConfigurationException extends Exception {
  public ConfigurationException(final String message) {
    super(message);
  }

  public ConfigurationException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
