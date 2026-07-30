package org.otel.agent.config.model;

/**
 * A property segment with an index accessor in a dynamic attribute path
 * (e.g. {@code [0]} in {@code Foo.items[0].name}).
 *
 * <p>Resolved at runtime by {@link org.otel.agent.runtime.resolver.ValueResolver}, which first
 * resolves the property (typically a {@code List} or array) and then accesses the element at
 * the given index.
 *
 * <p>Properties:
 * <ul>
 *   <li>{@code propertyName} — validated Java identifier for the property to access</li>
 *   <li>{@code index} — non-negative integer index into the resolved collection or array</li>
 * </ul>
 */
public record IndexedPropertySegment(String propertyName, int index) implements PathSegment {}
