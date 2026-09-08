package org.otel.agent.bridge.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.otel.agent.bridge.ReloadResult;
import org.otel.agent.bridge.RuntimeState;
import org.otel.agent.bridge.runtime.RuntimeEngine;
import org.otel.agent.config.model.CompiledConfiguration;
import org.otel.agent.config.parser.ConfigurationException;
import org.otel.agent.config.parser.ConfigurationParser;
import org.otel.agent.utils.Base64Util;
import org.otel.agent.webserver.ConfigWebserver;

/** Coordinates configuration sources while delegating each concern to a focused component. */
public final class RuntimeConfiguration {
  private final RuntimeEngine runtime;
  private final RuntimeConfigurationPublisher publisher;
  private final ConfigurationRootScanner rootScanner;
  private final RuntimeConfigurationFileReloader fileReloader;
  private final AtomicBoolean webserverStarted = new AtomicBoolean();

  public RuntimeConfiguration() {
    runtime = new RuntimeEngine();
    publisher = new RuntimeConfigurationPublisher(runtime);
    rootScanner = new ConfigurationRootScanner(runtime);
    fileReloader = new RuntimeConfigurationFileReloader(publisher::reload);
  }

  public void publish(
      final ClassLoader loader, final CompiledConfiguration configuration, final String xml) {
    publisher.publish(loader, configuration, xml);
  }

  public void initialize(final ClassLoader loader) {
    initialize(
        loader,
        System.getenv("OTEL_CUSTOM_AGENT_CONFIG_FILE"),
        System.getenv("OTEL_CUSTOM_AGENT_CONFIG_RELOAD_INTERVAL"),
        System.getenv("OTEL_CUSTOM_AGENT_CONFIG"));
  }

  public void initialize(
      final ClassLoader loader,
      final String filePathValue,
      final String intervalValue,
      final String encodedConfiguration) {
    if (isInitialized(loader)) return;

    final Path file = configFile(filePathValue);
    if (file != null) {
      startFileReload(file, intervalValue);
      startWebserver();
      if (initializeFile(loader, file)) return;
    }

    initializeEnvironment(loader, encodedConfiguration);
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

  public String activeXml() {
    return runtime.activeXml();
  }

  public void reset() {
    fileReloader.stop();
    ConfigWebserver.stop();
    webserverStarted.set(false);
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
      if (!initializeXml(loader, xml)) {
        logFileFailure(file, "invalid configuration");
        return false;
      }
      fileReloader.markLoaded(xml);
      return true;
    } catch (final IOException exception) {
      logFileFailure(file, exception.getMessage());
      return false;
    }
  }

  private boolean initializeXml(final ClassLoader loader, final String xml) {
    rootScanner.prepare(xml, loader);
    try {
      final CompiledConfiguration configuration = new ConfigurationParser().parseXml(xml, loader);
      publisher.publish(loader, configuration, xml);
      return true;
    } catch (final ConfigurationException exception) {
      return false;
    }
  }

  private void initializeEnvironment(final ClassLoader loader, final String encodedConfiguration) {
    if (encodedConfiguration == null) {
      runtime.disable(loader);
      return;
    }

    startWebserver();
    final String xml = decodeToXml(encodedConfiguration);
    if (xml != null) rootScanner.prepare(xml, loader);
    try {
      final CompiledConfiguration configuration =
          new ConfigurationParser().parse(encodedConfiguration, loader);
      publisher.publish(loader, configuration, xml);
    } catch (final ConfigurationException exception) {
      runtime.disable(loader);
    }
  }

  private void startWebserver() {
    if (!webserverStarted.compareAndSet(false, true)) return;
    try {
      ConfigWebserver.start();
    } catch (final Exception exception) {
      System.getLogger(RuntimeConfiguration.class.getName())
          .log(System.Logger.Level.WARNING, "config webserver failed to start", exception);
    }
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

  private static String decodeToXml(final String encoded) {
    try {
      return new String(Base64Util.decode(encoded), StandardCharsets.UTF_8);
    } catch (final ConfigurationException exception) {
      return null;
    }
  }

  private static void logFileFailure(final Path file, final String reason) {
    System.getLogger(RuntimeConfiguration.class.getName())
        .log(System.Logger.Level.WARNING, "config file reload failed for {0}: {1}", file, reason);
  }
}
