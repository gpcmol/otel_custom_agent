package org.otel.agent.bridge;

import java.util.List;
import org.otel.agent.config.model.CompiledConfiguration;
import org.otel.agent.runtime.model.ExitPointIndex;

/**
 * Immutable snapshot of the agent's runtime state for a single application class loader.
 *
 * <p>Stored in {@link RuntimeBridge}'s {@code STATES} map, keyed by the application class
 * loader. The instrumentation advice reads this state on every exit-point method exit to
 * decide whether enrichment is enabled and to access the compiled exit-point index.
 *
 * <p>Properties:
 * <ul>
 *   <li>{@code enabled} — whether enrichment is active for this loader; {@code false} when no
 *       config was loaded or config was invalid at startup
 *   <li>{@code configuration} — the immutable {@link CompiledConfiguration} (static + exit-point
 *       rules) parsed from XML; empty when disabled
 *   <li>{@code exitIndex} — an {@link ExitPointIndex} built from the exit-point rules, optimized
 *       for {@code (Class, methodName)} lookup by advice; {@code null} when disabled
 * </ul>
 *
 * <p>Instances are immutable and atomically replaced on reload. In-flight enrichments read
 * the old snapshot until the new one is published.
 */
public record RuntimeState(
    boolean enabled, CompiledConfiguration configuration, ExitPointIndex exitIndex) {
  public static RuntimeState disabled() {
    return new RuntimeState(false, new CompiledConfiguration(List.of(), List.of()), null);
  }

  public static RuntimeState enabled(
      final CompiledConfiguration configuration, final ExitPointIndex exitIndex) {
    return new RuntimeState(true, configuration, exitIndex);
  }
}
