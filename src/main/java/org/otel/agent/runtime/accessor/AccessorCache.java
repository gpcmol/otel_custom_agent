package org.otel.agent.runtime.accessor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.otel.agent.runtime.cache.FifoCache;

/**
 * Cache of {@link Accessor} instances for (class, property) pairs, avoiding repeated
 * reflective method/field lookup.
 *
 * <p>Discovery order: tries {@code getX()} getter → {@code isX()} boolean getter → public
 * field access. Only no-arg, non-static methods are considered. Boolean getters are only
 * accepted when the return type is {@code boolean} or {@code Boolean}.
 *
 * <p>Caching: uses a bounded {@link FifoCache} with capacity 1000. A {@code NULL_ACCESSOR}
 * sentinel distinguishes "not found" from "found but null" — when a property cannot be
 * discovered, the sentinel is cached so subsequent lookups return {@code null} without
 * retrying the reflection.
 *
 * <p>Thread safety: the underlying {@link FifoCache} uses {@link ConcurrentHashMap} for
 * lock-free reads, making cache hits safe for concurrent access from instrumentation advice.
 */
public final class AccessorCache {
  private static final Accessor NULL_ACCESSOR = target -> null;

  private final FifoCache<Key, Accessor> values = new FifoCache<>(1000);

  public Accessor find(final Class<?> type, final String property) {
    final Key key = new Key(type, property);
    final Accessor cached = values.get(key);
    if (cached != null) return cached == NULL_ACCESSOR ? null : cached;
    final Accessor accessor = discover(type, property);
    values.put(key, accessor == null ? NULL_ACCESSOR : accessor);
    return accessor;
  }

  private Accessor discover(final Class<?> type, final String property) {
    final String suffix = Character.toUpperCase(property.charAt(0)) + property.substring(1);
    final Accessor getter = method(type, "get" + suffix, false);
    if (getter != null) return getter;
    final Accessor booleanGetter = method(type, "is" + suffix, true);
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

  private Accessor method(final Class<?> type, final String name, final boolean requireBoolean) {
    try {
      final Method method = type.getMethod(name);
      if (method.getParameterCount() != 0
          || method.getReturnType() == void.class
          || Modifier.isStatic(method.getModifiers())) return null;
      if (requireBoolean
          && method.getReturnType() != boolean.class
          && method.getReturnType() != Boolean.class) return null;
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
