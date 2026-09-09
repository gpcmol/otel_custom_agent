package org.otel.agent.config.model;

import java.util.List;

/**
 * Immutable, thread-safe snapshot of a fully parsed configuration.
 *
 * <p>Produced by {@link org.otel.agent.config.parser.ConfigurationParser} and consumed by
 * {@link org.otel.agent.runtime.model.ExitPointIndex} to build the runtime exit-point index.
 * Contains two disjoint rule sets:
 * <ul>
 *   <li>{@link #staticRules} — constant key/value pairs applied to every exit-point span
 *   <li>{@link #exitPoints} — path-based rules grouped by declared exit-point method, resolved
 *       at runtime from the advice context ({@code $this} / {@code $argN} / {@code $return})
 * </ul>
 *
 * <p>Instances are immutable (lists are copied on construction) and safely publishable across
 * threads. A new instance replaces the old one atomically on reload via
 * {@link org.otel.agent.bridge.RuntimeBridge#publish}.
 */
public record CompiledConfiguration(
    List<StaticAttributeRule> staticRules, List<ExitPoint> exitPoints) {
  public CompiledConfiguration {
    staticRules = List.copyOf(staticRules);
    exitPoints = List.copyOf(exitPoints);
  }
}
