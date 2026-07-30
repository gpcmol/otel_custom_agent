package org.otel.agent.runtime.accessor;

/**
 * Functional interface for reading a property value from an object at runtime.
 *
 * <p>Created by {@link AccessorCache} via reflection (method invoke or field get) and cached
 * as a lambda to avoid repeated reflective lookup. The {@link #read(Object)} method returns
 * the property value or {@code null} if the accessor fails (e.g. due to visibility or
 * {@link IllegalAccessException}).
 *
 * <p>Used by {@link ValueResolver} to navigate dynamic attribute paths.
 */
@FunctionalInterface
public interface Accessor {
  Object read(Object target);
}
