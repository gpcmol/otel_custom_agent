package org.otel.agent.bridge.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.otel.agent.bridge.ReloadResult;
import org.otel.agent.bridge.RuntimeState;
import org.otel.agent.config.model.CompiledConfiguration;
import org.otel.agent.config.parser.ConfigurationException;
import org.otel.agent.config.parser.ConfigurationParser;
import org.otel.agent.runtime.model.ExitPointIndex;

/** Runtime facade used by configuration code without exposing runtime implementations. */
public final class RuntimeEngine {
  private final RuntimeStateStore states;
  private final ApplicationClassLoaderBridge applicationBridge;

  public RuntimeEngine() {
    states = new RuntimeStateStore();
    applicationBridge = new ApplicationClassLoaderBridge();
  }

  public void publish(final ClassLoader loader, final CompiledConfiguration configuration) {
    final RuntimeState state =
        RuntimeState.enabled(configuration, new ExitPointIndex(configuration));
    states.publish(loader, state);
    applicationBridge.pushState(loader, state);
  }

  public synchronized ReloadResult reload(final String xml) {
    final List<String> failures = new ArrayList<>();
    int updated = 0;
    int staticCount = 0;
    int exitCount = 0;

    for (final ClassLoader loader : states.loaders()) {
      try {
        final CompiledConfiguration configuration =
            new ConfigurationParser().parseXml(xml, loader);
        if (!applicationBridge.reload(xml, loader)) {
          failures.add("reload failed in application classloader");
          continue;
        }
        updated++;
        staticCount = configuration.staticRules().size();
        exitCount = configuration.exitPoints().size();
      } catch (final ConfigurationException exception) {
        failures.add(category(exception));
      }
    }
    return new ReloadResult(updated, staticCount, exitCount, failures);
  }

  public void prepareForInitialization(
      final ClassLoader loader, final Map<String, Set<String>> methodsByClass) {
    states.prepareForInitialization(loader, methodsByClass);
  }

  public void disable(final ClassLoader loader) {
    states.disable(loader);
  }

  public boolean isInitialized(final ClassLoader loader) {
    synchronized (states) {
      return states.isEnabled(loader) && states.state(loader).enabled();
    }
  }

  public RuntimeState state(final ClassLoader loader) {
    return states.state(loader);
  }

  public boolean enabled(final ClassLoader loader) {
    return states.isEnabled(loader);
  }

  public Set<String> rootNames() {
    return states.rootNames();
  }

  public Map<String, Set<String>> rootMethods() {
    return states.rootMethods();
  }

  public void reset() {
    states.reset();
  }

  private static String category(final ConfigurationException exception) {
    final String message = exception.getMessage();
    final int colon = message.indexOf(':');
    return colon < 0 ? message : message.substring(0, colon);
  }
}
