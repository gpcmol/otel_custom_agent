package org.otel.agent.config.model;

/**
 * A named property segment in a dynamic attribute path (e.g. {@code .name} in {@code Foo.bar.name}).
 *
 * <p>Resolved at runtime by {@link org.otel.agent.runtime.resolver.ValueResolver}, which looks up
 * the property by name on the current object via field access or getter method.
 *
 * <p>Property:
 * <ul>
 *   <li>{@code propertyName} — validated Java identifier (non-empty, starts with valid identifier start char)</li>
 * </ul>
 */
public record PropertySegment(String propertyName) implements PathSegment {}
