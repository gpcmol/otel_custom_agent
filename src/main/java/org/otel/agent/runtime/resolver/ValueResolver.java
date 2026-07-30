package org.otel.agent.runtime.resolver;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.otel.agent.config.model.IndexedPropertySegment;
import org.otel.agent.config.model.PathSegment;
import org.otel.agent.config.model.PropertySegment;
import org.otel.agent.runtime.accessor.Accessor;
import org.otel.agent.runtime.accessor.AccessorCache;

public final class ValueResolver {
  private final AccessorCache accessors;

  public ValueResolver(final AccessorCache accessors) {
    this.accessors = accessors;
  }

  public Object resolve(final Object receiver, final List<PathSegment> segments) {
    if (receiver == null || segments.isEmpty()) return null;
    return resolveValue(receiver, segments, 0);
  }

  private Object resolveValue(
      final Object current, final List<PathSegment> segments, final int position) {
    if (current == null || current instanceof Map<?, ?>) return null;
    if (isCollectionLike(current) && !(segments.get(position) instanceof IndexedPropertySegment)) {
      return resolveCollection(current, segments, position);
    }
    final PathSegment segment = segments.get(position);
    final Object value = read(current, segment);
    if (value == null) return null;
    if (position == segments.size() - 1) return value;
    return resolveValue(value, segments, position + 1);
  }

  private List<Object> resolveCollection(
      final Object current, final List<PathSegment> segments, final int position) {
    final List<Object> values = new ArrayList<>();
    if (current.getClass().isArray()) {
      for (int i = 0; i < Array.getLength(current); i++) {
        collect(values, Array.get(current, i), segments, position);
      }
    } else {
      for (final Object element : (Iterable<?>) current) {
        collect(values, element, segments, position);
      }
    }
    return values;
  }

  private void collect(
      final List<Object> values,
      final Object element,
      final List<PathSegment> segments,
      final int position) {
    final Object value = resolveValue(element, segments, position);
    if (value instanceof Collection<?> nested) {
      for (final Object nestedValue : nested) if (nestedValue != null) values.add(nestedValue);
    } else if (value != null && value.getClass().isArray()) {
      for (int i = 0; i < Array.getLength(value); i++) {
        final Object nestedValue = Array.get(value, i);
        if (nestedValue != null) values.add(nestedValue);
      }
    } else if (value != null) values.add(value);
  }

  private Object read(final Object current, final PathSegment segment) {
    if (segment instanceof PropertySegment(final String propertyName)) {
      final Accessor accessor = accessors.find(current.getClass(), propertyName);
      return accessor == null ? null : accessor.read(current);
    }
    final IndexedPropertySegment indexed = (IndexedPropertySegment) segment;
    final Accessor accessor = accessors.find(current.getClass(), indexed.propertyName());
    final Object value = accessor == null ? null : accessor.read(current);
    return select(value, indexed.index());
  }

  private Object select(final Object value, final int index) {
    if (value == null) return null;
    if (value.getClass().isArray())
      return index < Array.getLength(value) ? Array.get(value, index) : null;
    if (value instanceof List<?> list)
      return index >= 0 && index < list.size() ? list.get(index) : null;
    if (value instanceof Iterable<?> iterable) {
      int current = 0;
      for (final Object element : iterable) {
        if (current++ == index) return element;
      }
    }
    return null;
  }

  private boolean isCollectionLike(final Object value) {
    return value.getClass().isArray() || value instanceof Iterable<?>;
  }
}
