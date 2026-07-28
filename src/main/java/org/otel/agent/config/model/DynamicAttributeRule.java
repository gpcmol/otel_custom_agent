package org.otel.agent.config.model;

import java.util.List;

public record DynamicAttributeRule(
    String key, String rootClassName, Class<?> rootClass, List<PathSegment> segments) {
  public DynamicAttributeRule {
    segments = List.copyOf(segments);
  }
}
