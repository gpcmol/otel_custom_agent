package org.otel.agent.bridge;

import java.util.List;

public record ReloadResult(
    int updatedClassloaders, int staticRuleCount, int dynamicRuleCount, List<String> failures) {
  public ReloadResult {
    failures = List.copyOf(failures);
  }

  public boolean succeeded() {
    return failures.isEmpty();
  }
}
