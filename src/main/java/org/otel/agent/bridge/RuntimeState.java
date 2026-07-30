package org.otel.agent.bridge;

import java.util.List;
import org.otel.agent.config.model.CompiledConfiguration;
import org.otel.agent.runtime.model.RuleIndex;

/**
 * Immutable snapshot of the agent's runtime state for a single application class loader.
 *
 * <p>Stored in {@link org.otel.agent.bridge.RuntimeBridge}'s {@code STATES} map, keyed by the
 * application class loader. The instrumentation advice reads this state on every instrumented
 * method exit to decide whether enrichment is enabled and to access the compiled rules.
 *
 * <p>Properties:
 * <ul>
 *   <li>{@code enabled} — whether enrichment is active for this loader; {@code false} when no
 *       config was loaded or config was invalid at startup</li>
 *   <li>{@code configuration} — the immutable {@link CompiledConfiguration} (static + dynamic
 *       rules) parsed from XML; empty when disabled</li>
 *   <li>{@code ruleIndex} — a {@link RuleIndex} built from the dynamic rules, optimized for
 *       {@code isAssignableFrom} lookups by runtime type; {@code null} when disabled</li>
 * </ul>
 *
 * <p>Instances are immutable and atomically replaced on reload. In-flight enrichments read
 * the old snapshot until the new one is published.
 */
public record RuntimeState(
    boolean enabled, CompiledConfiguration configuration, RuleIndex ruleIndex) {
  public static RuntimeState disabled() {
    return new RuntimeState(false, new CompiledConfiguration(List.of(), List.of()), null);
  }

  public static RuntimeState enabled(
      final CompiledConfiguration configuration, final RuleIndex ruleIndex) {
    return new RuntimeState(true, configuration, ruleIndex);
  }
}
