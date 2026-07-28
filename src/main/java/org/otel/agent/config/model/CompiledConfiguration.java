package org.otel.agent.config.model;

import java.util.List;

public record CompiledConfiguration(
    List<StaticAttributeRule> staticRules, List<DynamicAttributeRule> dynamicRules) {
  public CompiledConfiguration {
    staticRules = List.copyOf(staticRules);
    dynamicRules = List.copyOf(dynamicRules);
  }
}
