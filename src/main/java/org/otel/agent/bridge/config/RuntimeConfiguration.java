package org.otel.agent.bridge.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.otel.agent.bridge.ReloadResult;
import org.otel.agent.bridge.RuntimeState;
import org.otel.agent.bridge.runtime.RuntimeEngine;
import org.otel.agent.config.model.CompiledConfiguration;
import org.otel.agent.config.parser.ConfigurationException;
import org.otel.agent.config.parser.ConfigurationParser;

/** Coordinates configuration sources while delegating each concern to a focused component. */
public final class RuntimeConfiguration {
  private final RuntimeEngine runtime;
  private final RuntimeConfigurationPublisher publisher;
  private final ConfigurationRootScanner rootScanner;
  private final RuntimeConfigurationFileReloader fileReloader;

  public RuntimeConfiguration() {
    runtime = new RuntimeEngine();
    publisher = new RuntimeConfigurationPublisher(runtime);
    rootScanner = new ConfigurationRootScanner(runtime);
    fileReloader = new RuntimeConfigurationFileReloader(publisher::reload);
  }

  public void publish(final ClassLoader loader, final CompiledConfiguration configuration) {
    publisher.publish(loader, configuration);
  }

  public void initialize(final ClassLoader loader) {
    initialize(
        loader,
        System.getenv("OTEL_CUSTOM_AGENT_CONFIG_FILE"),
        System.getenv("OTEL_CUSTOM_AGENT_CONFIG_RELOAD_INTERVAL"));
  }

  public void initialize(
      final ClassLoader loader, final String filePathValue, final String intervalValue) {
    if (isInitialized(loader)) return;

    final Path file = configFile(filePathValue);
    if (file != null) {
      startFileReload(file, intervalValue);
      if (initializeFile(loader, file)) return;
    }
  }

  public ReloadResult reload(final String xml) {
    return publisher.reload(xml);
  }

  public RuntimeState state(final ClassLoader loader) {
    return runtime.state(loader);
  }

  public boolean enabled(final ClassLoader loader) {
    return runtime.enabled(loader);
  }

  public Set<String> rootNames() {
    return runtime.rootNames();
  }

  public Map<String, Set<String>> rootMethods() {
    return runtime.rootMethods();
  }

  public void reset() {
    fileReloader.stop();
    publisher.reset();
  }

  private boolean isInitialized(final ClassLoader loader) {
    return runtime.isInitialized(loader);
  }

  private void startFileReload(final Path file, final String intervalValue) {
    fileReloader.start(file, reloadInterval(intervalValue));
  }

  private boolean initializeFile(final ClassLoader loader, final Path file) {
    try {
      final String xml = Files.readString(file, StandardCharsets.UTF_8);
      final ConfigurationException failure = initializeXml(loader, xml);
      if (failure != null) {
        if (!isClassLoaderMismatch(failure)) logFileFailure(file, failure.getMessage());
        return false;
      }
      fileReloader.markLoaded(xml);
      return true;
    } catch (final IOException exception) {
      logFileFailure(file, exception.getMessage());
      return false;
    }
  }

  private ConfigurationException initializeXml(final ClassLoader loader, final String xml) {
    rootScanner.prepare(xml, loader);
    try {
      final CompiledConfiguration configuration = new ConfigurationParser().parseXml(xml, loader);
      publisher.publish(loader, configuration);
      return null;
    } catch (final ConfigurationException exception) {
      return exception;
    }
  }

  private static boolean isClassLoaderMismatch(final ConfigurationException exception) {
    return exception.getMessage() != null
        && exception.getMessage().startsWith("exit-point class cannot be resolved:");
  }

  private static Path configFile(final String value) {
    if (value == null || value.isBlank()) return null;
    return Path.of(value.trim());
  }

  public static long reloadInterval(final String value) {
    if (value == null || value.isBlank()) return 5;
    try {
      final long seconds = Long.parseLong(value.trim());
      return seconds > 0 ? seconds : 5;
    } catch (final NumberFormatException exception) {
      return 5;
    }
  }

  private static void logFileFailure(final Path file, final String reason) {
    System.getLogger(RuntimeConfiguration.class.getName())
        .log(System.Logger.Level.WARNING, "config file reload failed for {0}: {1}", file, reason);
  }
}
