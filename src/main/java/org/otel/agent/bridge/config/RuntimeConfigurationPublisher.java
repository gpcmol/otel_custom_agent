package org.otel.agent.bridge.config;

import org.otel.agent.bridge.ReloadResult;
import org.otel.agent.bridge.runtime.RuntimeEngine;
import org.otel.agent.config.model.CompiledConfiguration;

/** Publishes compiled configuration across the extension/application classloader boundary. */
final class RuntimeConfigurationPublisher {
  private final RuntimeEngine runtime;

  RuntimeConfigurationPublisher(final RuntimeEngine runtime) {
    this.runtime = runtime;
  }

  void publish(final ClassLoader loader, final CompiledConfiguration configuration, final String xml) {
    runtime.publish(loader, configuration, xml);
    logConfiguration(configuration);
  }

  ReloadResult reload(final String xml) {
    return runtime.reload(xml);
  }

  void reset() {
    runtime.reset();
  }

  private static void logConfiguration(final CompiledConfiguration configuration) {
    final long rootCount =
        configuration.exitPoints().stream()
            .map(exitPoint -> exitPoint.rootClass())
            .distinct()
            .count();
    System.getLogger(RuntimeConfigurationPublisher.class.getName())
        .log(
            System.Logger.Level.INFO,
            "enabled static={0} exit-points={1} roots={2}",
            configuration.staticRules().size(),
            configuration.exitPoints().size(),
            rootCount);
  }
}
