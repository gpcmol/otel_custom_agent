package org.otel.agent.config.model;

import java.util.List;

/**
 * A runtime-resolved span attribute sourced from instrumented object properties.
 *
 * <p>Used at runtime by {@link org.otel.agent.runtime.EnrichmentRuntime#write}, which queries
 * {@link org.otel.agent.runtime.model.RuleIndex#applicable(Class)} to find rules whose
 * {@link #rootClass} is assignable from the receiver's runtime type. The rule's
 * {@link #segments} are then resolved against the receiver object via
 * {@link org.otel.agent.runtime.resolver.ValueResolver}, converted by
 * {@link org.otel.agent.telemetry.AttributeConverter}, and written to the span.
 *
 * <p>Properties:
 * <ul>
 *   <li>{@code key} — OTel attribute key (max 255 chars, validated for uniqueness)</li>
 *   <li>{@code rootClassName} — dotted class name used for static matching and reload diffing</li>
 *   <li>{@code rootClass} — resolved {@link Class} object, used for {@code isAssignableFrom} checks</li>
 *   <li>{@code segments} — ordered path segments navigating from the root object to the target property</li>
 * </ul>
 */
public record DynamicAttributeRule(
    String key, String rootClassName, Class<?> rootClass, List<PathSegment> segments) {
  public DynamicAttributeRule {
    segments = List.copyOf(segments);
  }
}
