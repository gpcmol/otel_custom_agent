package org.otel.agent.runtime.accessor;

import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.otel.agent.runtime.cache.FifoCache;

/**
 * Cache of {@link Accessor} instances for (class, property) pairs, avoiding repeated reflective
 * method/field lookup and bypassing {@link Method#invoke} / {@link Field#get} on the hot path.
 *
 * <p>Discovery order: tries {@code getX()} getter → {@code isX()} boolean getter → public field.
 * Only no-arg, non-static methods are considered. Boolean getters are only accepted when the return
 * type is {@code boolean} or {@code Boolean}.
 *
 * <p>Binding: each resolved {@link Method}/{@link Field} is converted to a {@link MethodHandle}
 * and {@linkplain LambdaMetafactory#metafactory lambdified} into a direct {@link Accessor} call
 * site. The generated lambda is JIT-compiled, so per-call cost approaches a direct invocation
 * (no per-call access checks, no varargs array, no reflective exception wrapping) — relevant
 * because {@link org.otel.agent.runtime.resolver.ValueResolver} hits this on every instrumented
 * span. If lambdification fails (e.g. module-access divergence between {@code getMethod} and
 * {@code unreflect}), the cold discovery path falls back to the original reflective accessor so
 * behaviour is preserved.
 *
 * <p>Caching: bounded {@link FifoCache} (capacity 1000). A {@code NULL_ACCESSOR} sentinel
 * distinguishes "not found" from "found but null" so subsequent lookups skip discovery.
 *
 * <p>Thread safety: {@link FifoCache} is backed by {@link java.util.concurrent.ConcurrentHashMap},
 * so cache hits are safe to read concurrently from instrumentation advice.
 */
public final class AccessorCache {
  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
  private static final MethodType ACCESSOR_TYPE = MethodType.methodType(Object.class, Object.class);
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
      return fieldAccessor(field);
    } catch (final NoSuchFieldException ignored) {
      return null;
    }
  }

  private Accessor method(final Class<?> type, final String name, final boolean requireBoolean) {
    final Method method;
    try {
      method = type.getMethod(name);
    } catch (final NoSuchMethodException ignored) {
      return null;
    }
    if (method.getParameterCount() != 0
        || method.getReturnType() == void.class
        || Modifier.isStatic(method.getModifiers())) return null;
    if (requireBoolean
        && method.getReturnType() != boolean.class
        && method.getReturnType() != Boolean.class) return null;
    try {
      return lambdify(LOOKUP.unreflect(method));
    } catch (final Throwable ignored) {
      // ponytail: cold-path fallback; hot path is normally the LMF accessor. Module-access
      // divergence between getMethod and unreflect is the only realistic trigger.
      return target -> {
        try {
          return method.invoke(target);
        } catch (final ReflectiveOperationException | RuntimeException ignored2) {
          return null;
        }
      };
    }
  }

  private Accessor fieldAccessor(final Field field) {
    try {
      return lambdify(LOOKUP.unreflectGetter(field));
    } catch (final Throwable ignored) {
      return target -> {
        try {
          return field.get(target);
        } catch (final ReflectiveOperationException | RuntimeException ignored2) {
          return null;
        }
      };
    }
  }

  private static Accessor lambdify(final MethodHandle handle) throws Throwable {
    // ponytail: asType once to box primitive returns / cast receiver; the LMF lambda folds the
    // conversion into bytecode the JIT inlines.
    final MethodHandle adapted = handle.asType(ACCESSOR_TYPE);
    final CallSite site =
        LambdaMetafactory.metafactory(
            LOOKUP,
            "read",
            MethodType.methodType(Accessor.class),
            ACCESSOR_TYPE,
            adapted,
            ACCESSOR_TYPE);
    return (Accessor) site.getTarget().invoke();
  }

  private record Key(Class<?> type, String property) {}
}
