package org.otel.agent.telemetry;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

public final class AttributeConverter {
  public AttributeValue convert(final Object value) {
    if (value == null) return null;
    if (value instanceof Character character) return new AttributeValue(character.toString());
    if (value instanceof Enum<?> enumeration) return new AttributeValue(enumeration.name());
    if (value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long) return new AttributeValue(((Number) value).longValue());
    if (value instanceof BigInteger integer) {
      try {
        return new AttributeValue(integer.longValueExact());
      } catch (final ArithmeticException ignored) {
        return null;
      }
    }
    if (value instanceof Float || value instanceof Double) return finiteDouble((Number) value);
    if (value instanceof BigDecimal decimal) return finiteDouble(decimal);
    if (value instanceof String || value instanceof Boolean) return new AttributeValue(value);
    if (value.getClass().isArray()) return convertArray(value);
    if (value instanceof Iterable<?> iterable) return convertIterable(iterable);
    return null;
  }

  private AttributeValue finiteDouble(final Number value) {
    final double converted = value.doubleValue();
    return Double.isFinite(converted) ? new AttributeValue(converted) : null;
  }

  private AttributeValue convertArray(final Object value) {
    final List<Object> values = new ArrayList<>();
    for (int i = 0; i < Array.getLength(value); i++) {
      final Object element = Array.get(value, i);
      if (element == null) continue;
      final Object converted = convertScalar(element);
      if (converted == null) return null;
      values.add(converted);
    }
    return new AttributeValue(List.copyOf(values));
  }

  private AttributeValue convertIterable(final Iterable<?> iterable) {
    final List<Object> values = new ArrayList<>();
    Class<?> type = null;
    for (final Object value : iterable) {
      if (value == null) continue;
      final Object converted = convertScalar(value);
      if (converted == null) return null;
      if (type == null) type = converted.getClass();
      if (type != converted.getClass()) return null;
      values.add(converted);
    }
    return new AttributeValue(List.copyOf(values));
  }

  private Object convertScalar(final Object value) {
    final AttributeValue converted = convert(value);
    return converted == null || converted.value() instanceof List<?> ? null : converted.value();
  }
}
