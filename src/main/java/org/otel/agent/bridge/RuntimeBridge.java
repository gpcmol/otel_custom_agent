package org.otel.agent.bridge;

import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.otel.agent.config.model.CompiledConfiguration;
import org.otel.agent.config.model.DynamicAttributeRule;
import org.otel.agent.config.parser.ConfigurationException;
import org.otel.agent.config.parser.ConfigurationParser;
import org.otel.agent.runtime.model.RuleIndex;

/** Minimal entry point shared by instrumentation advice and compiled runtime state. */
public final class RuntimeBridge {
  private static final RuntimeState DISABLED = RuntimeState.disabled();
  private static final Map<ClassLoader, RuntimeState> STATES = new WeakHashMap<>();
  private static final AtomicReference<Set<String>> ROOT_NAMES = new AtomicReference<>(Set.of());

  private RuntimeBridge() {}

  public static synchronized void publish(
      final ClassLoader loader, final CompiledConfiguration configuration) {
    STATES.put(loader, RuntimeState.enabled(configuration, new RuleIndex(configuration)));
    ROOT_NAMES.set(
        configuration.dynamicRules().stream()
            .map(DynamicAttributeRule::rootClassName)
            .collect(Collectors.toUnmodifiableSet()));
  }

  public static void initialize(final ClassLoader applicationLoader) {
    synchronized (RuntimeBridge.class) {
      if (STATES.containsKey(applicationLoader)) return;
    }
    final String encoded = System.getenv("OTEL_CUSTOM_AGENT_CONFIG");
    if (encoded == null) {
      return;
    }
    try {
      final CompiledConfiguration configuration =
          new ConfigurationParser().parse(encoded, applicationLoader);
      publish(applicationLoader, configuration);
      logConfig(configuration);
    } catch (final ConfigurationException exception) {
      synchronized (RuntimeBridge.class) {
        STATES.putIfAbsent(applicationLoader, DISABLED);
      }
    }
  }

  private static void logConfig(final CompiledConfiguration configuration) {
    System.getLogger(RuntimeBridge.class.getName())
        .log(
            System.Logger.Level.INFO,
            "enabled static={0} dynamic={1} roots={2}",
            configuration.staticRules().size(),
            configuration.dynamicRules().size(),
            configuration.dynamicRules().stream()
                .map(DynamicAttributeRule::rootClass)
                .distinct()
                .count());
  }

  public static synchronized RuntimeState state(final ClassLoader loader) {
    return STATES.getOrDefault(loader, DISABLED);
  }

  public static Set<String> rootClassNames() {
    return ROOT_NAMES.get();
  }
}
