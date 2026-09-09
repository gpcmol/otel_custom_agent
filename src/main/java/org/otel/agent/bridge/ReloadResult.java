package org.otel.agent.bridge;

import java.util.List;

/**
 * Result of a runtime configuration reload, returned by
 * {@link org.otel.agent.bridge.RuntimeBridge#reload(String)}.
 *
 * <p>Properties:
 * <ul>
 *   <li>{@code updatedClassloaders} — number of application class loaders whose state was
 *       successfully republished with the new configuration</li>
 *   <li>{@code staticRuleCount} — static rule count from the last successfully published
 *       configuration (0 if no loader was updated)</li>
 *   <li>{@code exitPointCount} — exit-point count from the last successfully published
 *       configuration (0 if no loader was updated)</li>
 *   <li>{@code failures} — per-loader failure messages for class loaders where reload failed;
 *       each message is the category extracted from the {@link org.otel.agent.config.parser.ConfigurationException}</li>
 * </ul>
 *
 * <p>A result is considered successful when {@link #failures} is empty. Partial success
 * (some loaders updated, some failed) is reported as a non-successful result with the
 * specific failure messages.
 */
public record ReloadResult(
    int updatedClassloaders, int staticRuleCount, int exitPointCount, List<String> failures) {
  public ReloadResult {
    failures = List.copyOf(failures);
  }

  public boolean succeeded() {
    return failures.isEmpty();
  }
}
