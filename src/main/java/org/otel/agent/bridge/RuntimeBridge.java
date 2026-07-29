package org.otel.agent.bridge;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.otel.agent.config.model.CompiledConfiguration;
import org.otel.agent.config.model.DynamicAttributeRule;
import org.otel.agent.config.parser.ConfigurationException;
import org.otel.agent.config.parser.ConfigurationParser;
import org.otel.agent.runtime.model.RuleIndex;
import org.otel.agent.webserver.ConfigWebserver;

/** Minimal entry point shared by instrumentation advice and compiled runtime state. */
public final class RuntimeBridge {
  private static final RuntimeState DISABLED = RuntimeState.disabled();
  private static final Map<ClassLoader, RuntimeState> STATES = new WeakHashMap<>();
  private static final AtomicReference<Set<String>> ROOT_NAMES = new AtomicReference<>(Set.of());
  private static final AtomicReference<String> ACTIVE_XML = new AtomicReference<>();
  private static final AtomicBoolean WEBSERVER_STARTED = new AtomicBoolean(false);

  private RuntimeBridge() {}

  public static synchronized void publish(
      final ClassLoader loader, final CompiledConfiguration configuration) {
    STATES.put(loader, RuntimeState.enabled(configuration, new RuleIndex(configuration)));
    ROOT_NAMES.set(
        configuration.dynamicRules().stream()
            .map(DynamicAttributeRule::rootClassName)
            .collect(Collectors.toUnmodifiableSet()));
  }

  public static void publish(
      final ClassLoader loader, final CompiledConfiguration configuration, final String xml) {
    publish(loader, configuration);
    ACTIVE_XML.set(xml);
  }

  public static void initialize(final ClassLoader applicationLoader) {
    synchronized (RuntimeBridge.class) {
      if (STATES.containsKey(applicationLoader)) return;
    }
    startWebserverIfNeeded();
    final String encoded = System.getenv("OTEL_CUSTOM_AGENT_CONFIG");
    if (encoded == null) {
      return;
    }
    try {
      final CompiledConfiguration configuration =
          new ConfigurationParser().parse(encoded, applicationLoader);
      final String xml = decodeToXml(encoded);
      publish(applicationLoader, configuration, xml);
      logConfig(configuration);
    } catch (final ConfigurationException exception) {
      synchronized (RuntimeBridge.class) {
        STATES.putIfAbsent(applicationLoader, DISABLED);
      }
    }
  }

  public static ReloadResult reload(final String xml) {
    final List<ClassLoader> loaders;
    synchronized (RuntimeBridge.class) {
      loaders = new ArrayList<>(STATES.keySet());
    }
    final List<String> failures = new ArrayList<>();
    int updated = 0;
    int staticCount = 0;
    int dynamicCount = 0;

    for (final ClassLoader loader : loaders) {
      try {
        final CompiledConfiguration configuration = new ConfigurationParser().parseXml(xml, loader);
        publish(loader, configuration);
        forwardReloadToApplicationClassloader(xml, loader);
        updated++;
        staticCount = configuration.staticRules().size();
        dynamicCount = configuration.dynamicRules().size();
      } catch (final ConfigurationException exception) {
        failures.add(exception.getMessage());
      }
    }

    if (updated > 0) {
      ACTIVE_XML.set(xml);
    }

    return new ReloadResult(updated, staticCount, dynamicCount, failures);
  }

  public static String activeXml() {
    return ACTIVE_XML.get();
  }

  private static void startWebserverIfNeeded() {
    if (!WEBSERVER_STARTED.compareAndSet(false, true)) return;
    try {
      ConfigWebserver.start();
    } catch (final Exception exception) {
      System.getLogger(RuntimeBridge.class.getName())
          .log(System.Logger.Level.WARNING, "config webserver failed to start", exception);
    }
  }

  private static void forwardReloadToApplicationClassloader(final String xml, final ClassLoader loader) {
    try {
      final Class<?> enrichmentRuntime = Class.forName("org.otel.agent.runtime.EnrichmentRuntime", true, loader);
      enrichmentRuntime.getMethod("reloadFromBridge", String.class).invoke(null, xml);
    } catch (final ClassNotFoundException | NoSuchMethodException | IllegalAccessException |
                 java.lang.reflect.InvocationTargetException ignored) {
    }
  }

  private static String decodeToXml(final String encoded) {
    try {
      return new String(ConfigurationParser.decode(encoded), StandardCharsets.UTF_8);
    } catch (final ConfigurationException exception) {
      return null;
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

  public static void resetForTesting() {
    ConfigWebserver.stop();
    WEBSERVER_STARTED.set(false);
    synchronized (RuntimeBridge.class) {
      STATES.clear();
    }
    ROOT_NAMES.set(Set.of());
    ACTIVE_XML.set(null);
  }
}
