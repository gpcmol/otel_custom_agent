package org.otel.agent.bridge;

import java.util.Map;
import java.util.Set;
import org.otel.agent.bridge.config.RuntimeConfiguration;
import org.otel.agent.config.model.CompiledConfiguration;

/**
 * Public entry point between the agent extension classloader and application classloaders.
 *
 * <p>This class intentionally contains only the bridge API. Configuration lifecycle, state storage,
 * reflection across classloaders, and TTL scheduling live in dedicated package classes.
 */
public final class RuntimeBridge {
  private static final RuntimeConfiguration SERVICE = new RuntimeConfiguration();

  private RuntimeBridge() {}

  public static synchronized void publish(
      final ClassLoader loader, final CompiledConfiguration configuration) {
    SERVICE.publish(loader, configuration, null);
  }

  public static synchronized void publish(
      final ClassLoader loader, final CompiledConfiguration configuration, final String xml) {
    SERVICE.publish(loader, configuration, xml);
  }

  public static void initialize(final ClassLoader loader) {
    SERVICE.initialize(loader);
  }

  static void initializeForTesting(
      final ClassLoader loader,
      final String filePath,
      final String reloadInterval,
      final String encodedConfiguration) {
    SERVICE.initialize(loader, filePath, reloadInterval, encodedConfiguration);
  }

  static long reloadIntervalForTesting(final String value) {
    return RuntimeConfiguration.reloadInterval(value);
  }

  public static ReloadResult reload(final String xml) {
    return SERVICE.reload(xml);
  }

  public static RuntimeState state(final ClassLoader loader) {
    return SERVICE.state(loader);
  }

  public static boolean enabled(final ClassLoader loader) {
    return SERVICE.enabled(loader);
  }

  public static Set<String> rootClassNames() {
    return SERVICE.rootNames();
  }

  public static Map<String, Set<String>> rootClassMethods() {
    return SERVICE.rootMethods();
  }

  public static String activeXml() {
    return SERVICE.activeXml();
  }

  public static void resetForTesting() {
    SERVICE.reset();
  }
}
