package org.otel.agent.telemetry;

/**
 * Converted attribute value. For list values, {@code componentType} is the OTel scalar type of the
 * elements ({@link String}, {@link Boolean}, {@link Long} or {@link Double}) when known, so empty
 * collections can still be written as a typed array.
 */
public record AttributeValue(Object value, Class<?> componentType) {
  public AttributeValue(final Object value) {
    this(value, null);
  }
}
