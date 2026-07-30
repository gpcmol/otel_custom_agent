package org.otel.agent.telemetry;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts arbitrary Java objects into OpenTelemetry-compatible {@link AttributeValue} instances.
 *
 * <p>OTel span attributes support only four scalar types — {@code String}, {@code Boolean},
 * {@code Long}, and {@code Double} — plus arrays of those types. This converter normalizes
 * arbitrary Java values (numbers, enums, characters, collections, arrays, etc.) into those
 * OTel primitives. Numeric types are widened to {@code Long} or {@code Double} as appropriate;
 * {@link java.math.BigDecimal} and {@link java.math.BigInteger} are converted with overflow
 * checks, returning {@code null} on failure. Empty or heterogeneous collections are rejected
 * so the resulting attribute is always type-homogeneous.
 */
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
    if (value instanceof Float || value instanceof Double) {
      final Double d = finiteDouble((Number) value);
      return d == null ? null : new AttributeValue(d);
    }
    if (value instanceof BigDecimal decimal) {
      final Double d = finiteDouble(decimal);
      return d == null ? null : new AttributeValue(d);
    }
    if (value instanceof String || value instanceof Boolean) return new AttributeValue(value);
    if (value.getClass().isArray()) return convertArray(value);
    if (value instanceof Iterable<?> iterable) return convertIterable(iterable);
    return null;
  }

  private static Double finiteDouble(final Number value) {
    final double converted = value.doubleValue();
    return Double.isFinite(converted) ? converted : null;
  }

  private AttributeValue convertArray(final Object value) {
    final List<Object> values = new ArrayList<>();
    Class<?> type = null;
    for (int i = 0; i < Array.getLength(value); i++) {
      final Object element = Array.get(value, i);
      if (element == null) continue;
      final Object converted = convertToScalar(element);
      if (converted == null) return null;
      if (type == null) type = converted.getClass();
      if (type != converted.getClass()) return null;
      values.add(converted);
    }
    return new AttributeValue(
        List.copyOf(values),
        type != null ? type : emptyComponentType(value.getClass().getComponentType()));
  }

  private AttributeValue convertIterable(final Iterable<?> iterable) {
    final List<Object> values = new ArrayList<>();
    Class<?> type = null;
    for (final Object value : iterable) {
      if (value == null) continue;
      final Object converted = convertToScalar(value);
      if (converted == null) return null;
      if (type == null) type = converted.getClass();
      if (type != converted.getClass()) return null;
      values.add(converted);
    }
    return new AttributeValue(List.copyOf(values), type);
  }

  private static Class<?> emptyComponentType(final Class<?> componentType) {
    if (componentType == String.class
        || componentType == Character.class
        || componentType == char.class
        || Enum.class.isAssignableFrom(componentType)) return String.class;
    if (componentType == Boolean.class || componentType == boolean.class) return Boolean.class;
    if (componentType == Float.class
        || componentType == float.class
        || componentType == Double.class
        || componentType == double.class
        || componentType == BigDecimal.class) return Double.class;
    if (componentType.isPrimitive() || Number.class.isAssignableFrom(componentType)) {
      return Long.class;
    }
    return null;
  }

  /** Converts a scalar element to its boxed OTel scalar without the {@link AttributeValue} wrapper. */
  private static Object convertToScalar(final Object value) {
    if (value == null) return null;
    if (value instanceof Character character) return character.toString();
    if (value instanceof Enum<?> enumeration) return enumeration.name();
    if (value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long) return ((Number) value).longValue();
    if (value instanceof BigInteger integer) {
      try {
        return integer.longValueExact();
      } catch (final ArithmeticException ignored) {
        return null;
      }
    }
    if (value instanceof Float || value instanceof Double) return finiteDouble((Number) value);
    if (value instanceof BigDecimal decimal) return finiteDouble(decimal);
    if (value instanceof String || value instanceof Boolean) return value;
    return null;
  }
}
