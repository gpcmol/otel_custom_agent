package org.otel.agent.config.model;

import java.util.List;

/**
 * One runtime-resolved span attribute rule attached to an {@link ExitPoint}.
 *
 * <p>Properties:
 * <ul>
 *   <li>{@code key} — OTel attribute key (max 255 chars, validated unique within the exit point)
 *   <li>{@code rootSource} — typed anchor ({@link RootSource}) that the segment path navigates from
 *   <li>{@code segments} — compiled immutable path segments (reusing
 *       {@link PropertySegment}/{@link IndexedPropertySegment})
 * </ul>
 *
 * <p>Replaces the previous {@code DynamicAttributeRule} of the per-instance-method model.
 * Resolution at runtime: {@link org.otel.agent.runtime.EnrichmentRuntime} selects the root via a
 * {@code switch} on {@link RootSource}, then hands the root and {@code segments} to
 * {@link org.otel.agent.runtime.resolver.ValueResolver}.
 */
public record ExitRule(String key, RootSource rootSource, List<PathSegment> segments) {
  public ExitRule {
    segments = List.copyOf(segments);
  }
}