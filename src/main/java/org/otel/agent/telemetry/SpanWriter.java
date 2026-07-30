package org.otel.agent.telemetry;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import java.util.List;

/**
 * Writes {@link AttributeValue} instances onto an OpenTelemetry {@link Span} as typed attributes.
 *
 * <p>Dispatches on the OTel scalar type (String, Boolean, Long, Double) and selects the correct
 * {@link AttributeKey} overload. For list values, the element type is determined either from the
 * first non-null element (for non-empty lists) or from {@link AttributeValue#componentType()}
 * (for empty lists, where element type inference is impossible). Unknown or mixed-type arrays are
 * silently dropped, matching the OTel attribute contract.
 */
public final class SpanWriter {
  public void write(final Span span, final String key, final AttributeValue attribute) {
    if (attribute == null || attribute.value() == null) return;
    final Object value = attribute.value();
    if (value instanceof String text) span.setAttribute(key, text);
    else if (value instanceof Boolean flag) span.setAttribute(key, flag);
    else if (value instanceof Long number) span.setAttribute(key, number);
    else if (value instanceof Double number) span.setAttribute(key, number);
    else if (value instanceof List<?> values)
      writeArray(span, key, values, attribute.componentType());
  }

  private void writeArray(
      final Span span, final String key, final List<?> values, final Class<?> componentType) {
    if (values.isEmpty()) {
      writeEmptyArray(span, key, componentType);
      return;
    }
    final Object first = values.getFirst();
    if (first instanceof String) span.setAttribute(AttributeKey.stringArrayKey(key), cast(values));
    else if (first instanceof Boolean)
      span.setAttribute(AttributeKey.booleanArrayKey(key), cast(values));
    else if (first instanceof Long) span.setAttribute(AttributeKey.longArrayKey(key), cast(values));
    else if (first instanceof Double)
      span.setAttribute(AttributeKey.doubleArrayKey(key), cast(values));
  }

  private void writeEmptyArray(final Span span, final String key, final Class<?> componentType) {
    if (componentType == String.class) {
      span.setAttribute(AttributeKey.stringArrayKey(key), List.of());
    } else if (componentType == Boolean.class) {
      span.setAttribute(AttributeKey.booleanArrayKey(key), List.of());
    } else if (componentType == Long.class) {
      span.setAttribute(AttributeKey.longArrayKey(key), List.of());
    } else if (componentType == Double.class) {
      span.setAttribute(AttributeKey.doubleArrayKey(key), List.of());
    }
    // Unknown element type: nothing safe to write.
  }

  @SuppressWarnings("unchecked")
  private <T> List<T> cast(final List<?> values) {
    return (List<T>) values;
  }
}
