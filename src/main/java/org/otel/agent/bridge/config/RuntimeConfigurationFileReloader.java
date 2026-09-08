package org.otel.agent.bridge.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.otel.agent.bridge.ReloadResult;

/** Polls a configuration file and retries failed updates on the next interval. */
final class RuntimeConfigurationFileReloader {
  private final Function<String, ReloadResult> reload;
  private volatile ScheduledExecutorService executor;
  private volatile String lastConfiguration;

  RuntimeConfigurationFileReloader(final Function<String, ReloadResult> reload) {
    this.reload = reload;
  }

  void start(final Path file, final long intervalSeconds) {
    if (executor != null) return;
    synchronized (this) {
      if (executor != null) return;
      final ScheduledExecutorService scheduledExecutor = newExecutor();
      executor = scheduledExecutor;
      scheduledExecutor.scheduleWithFixedDelay(
          () -> poll(file), intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }
  }

  void markLoaded(final String configuration) {
    lastConfiguration = configuration;
  }

  synchronized void stop() {
    if (executor != null) {
      executor.shutdownNow();
      executor = null;
    }
    lastConfiguration = null;
  }

  private void poll(final Path file) {
    final String configuration;
    try {
      configuration = Files.readString(file, StandardCharsets.UTF_8);
    } catch (final IOException exception) {
      logFailure(file, exception.getMessage());
      return;
    }
    if (configuration.equals(lastConfiguration)) return;

    final ReloadResult result = reload.apply(configuration);
    if (result.succeeded()) {
      lastConfiguration = configuration;
    } else {
      logFailure(file, String.join("; ", result.failures()));
    }
  }

  private static ScheduledExecutorService newExecutor() {
    return Executors.newSingleThreadScheduledExecutor(
        runnable -> {
          final Thread thread = new Thread(runnable, "otel-config-file-reloader");
          thread.setDaemon(true);
          return thread;
        });
  }

  private static void logFailure(final Path file, final String reason) {
    System.getLogger(RuntimeConfigurationFileReloader.class.getName())
        .log(System.Logger.Level.WARNING, "config file reload failed for {0}: {1}", file, reason);
  }
}
