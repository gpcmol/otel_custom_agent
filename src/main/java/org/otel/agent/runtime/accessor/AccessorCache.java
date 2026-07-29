package org.otel.agent.runtime.accessor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.otel.agent.runtime.cache.FifoCache;

public final class AccessorCache {
  private final FifoCache<Key, Accessor> values = new FifoCache<>(1000);

  public Accessor find(final Class<?> type, final String property) {
    final Key key = new Key(type, property);
    final Accessor cached = values.get(key);
    if (cached != null) return cached;
    final Accessor accessor = discover(type, property);
    values.put(key, accessor == null ? target -> null : accessor);
    return accessor;
  }

  private Accessor discover(final Class<?> type, final String property) {
    final String suffix = Character.toUpperCase(property.charAt(0)) + property.substring(1);
    final Accessor getter = method(type, "get" + suffix);
    if (getter != null) return getter;
    final Accessor booleanGetter = method(type, "is" + suffix);
    if (booleanGetter != null) return booleanGetter;
    try {
      final Field field = type.getField(property);
      return target -> {
        try {
          return field.get(target);
        } catch (final ReflectiveOperationException | RuntimeException ignored) {
          return null;
        }
      };
    } catch (final NoSuchFieldException ignored) {
      return null;
    }
  }

  private Accessor method(final Class<?> type, final String name) {
    try {
      final Method method = type.getMethod(name);
      if (method.getParameterCount() != 0 || method.getReturnType() == void.class) return null;
      return target -> {
        try {
          return method.invoke(target);
        } catch (final ReflectiveOperationException | RuntimeException ignored) {
          return null;
        }
      };
    } catch (final NoSuchMethodException ignored) {
      return null;
    }
  }

  private record Key(Class<?> type, String property) {}
}
