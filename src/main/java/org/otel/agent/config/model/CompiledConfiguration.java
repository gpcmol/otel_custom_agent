package org.otel.agent.config.model;

import java.util.List;

/**
 * Immutable, thread-safe snapshot of a fully parsed configuration.
 *
 * <p>Produced by {@link org.otel.agent.config.parser.ConfigurationParser} and consumed by
 * {@link org.otel.agent.runtime.model.RuleIndex} to build the runtime rule index. Contains two
 * disjoint rule sets:
 * <ul>
 *   <li>{@link #staticRules} — constant key/value pairs applied to every span unconditionally</li>
 *   <li>{@link #dynamicRules} — path-based rules resolved at runtime against instrumented objects</li>
 * </ul>
 *
 * <p>Instances are immutable (lists are copied on construction) and safely publishable across
 * threads. A new instance replaces the old one atomically on reload via
 * {@link org.otel.agent.bridge.RuntimeBridge#publish}.
 */
public record CompiledConfiguration(
    List<StaticAttributeRule> staticRules, List<DynamicAttributeRule> dynamicRules) {
  public CompiledConfiguration {
    staticRules = List.copyOf(staticRules);
    dynamicRules = List.copyOf(dynamicRules);
  }
}
