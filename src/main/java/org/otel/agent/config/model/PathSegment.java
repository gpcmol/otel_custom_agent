package org.otel.agent.config.model;

/**
 * Sealed interface for a single segment in a dynamic attribute path.
 *
 * <p>Used at runtime by {@link org.otel.agent.runtime.resolver.ValueResolver} to navigate from
 * the instrumented root object through property accessors and indexed collections to the
 * final value that becomes a span attribute.
 *
 * <p>Two implementations:
 * <ul>
 *   <li>{@link PropertySegment} — a named property accessed via getter or field</li>
 *   <li>{@link IndexedPropertySegment} — a property followed by a list/array index</li>
 * </ul>
 */
public sealed interface PathSegment permits PropertySegment, IndexedPropertySegment {}
