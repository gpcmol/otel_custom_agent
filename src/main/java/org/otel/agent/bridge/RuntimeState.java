package org.otel.agent.bridge;

import java.util.List;
import org.otel.agent.config.model.CompiledConfiguration;
import org.otel.agent.runtime.model.RuleIndex;

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
