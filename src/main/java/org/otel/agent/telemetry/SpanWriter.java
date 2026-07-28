package org.otel.agent.telemetry;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import java.util.List;

public final class SpanWriter {
  public void write(final Span span, final String key, final AttributeValue attribute) {
    if (attribute == null || attribute.value() == null) return;
    final Object value = attribute.value();
    if (value instanceof String text) span.setAttribute(key, text);
    else if (value instanceof Boolean flag) span.setAttribute(key, flag);
    else if (value instanceof Long number) span.setAttribute(key, number);
    else if (value instanceof Double number) span.setAttribute(key, number);
    else if (value instanceof List<?> values) writeArray(span, key, values);
  }

  private void writeArray(final Span span, final String key, final List<?> values) {
    if (values.isEmpty()) return;
    final Object first = values.getFirst();
    if (first instanceof String) span.setAttribute(AttributeKey.stringArrayKey(key), cast(values));
    else if (first instanceof Boolean)
      span.setAttribute(AttributeKey.booleanArrayKey(key), cast(values));
    else if (first instanceof Long) span.setAttribute(AttributeKey.longArrayKey(key), cast(values));
    else if (first instanceof Double)
      span.setAttribute(AttributeKey.doubleArrayKey(key), cast(values));
  }

  @SuppressWarnings("unchecked")
  private <T> List<T> cast(final List<?> values) {
    return (List<T>) values;
  }
}
