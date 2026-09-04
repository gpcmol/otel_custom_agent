package org.otel.agent.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.otel.agent.config.model.CompiledConfiguration;
import org.otel.agent.config.parser.ConfigurationParser;
import org.otel.agent.runtime.EnrichmentRuntime;

class ConfigTtlTest {
  private final ClassLoader loader = getClass().getClassLoader();

  @BeforeEach
  void setUp() {
    RuntimeBridge.resetForTesting();
  }

  @AfterEach
  void tearDown() {
    RuntimeBridge.resetForTesting();
  }

  @Test
  void publishWithTtlDisablesAfterExpiry() {
    publish("<configuration ttl=\"PT0.05S\"></configuration>");

    assertTrue(RuntimeBridge.state(loader).enabled());
    awaitDisabled(2_000);
    assertFalse(RuntimeBridge.state(loader).enabled());
  }

  @Test
  void reloadWithTtlReArms() {
    publish("<configuration ttl=\"PT0.05S\"></configuration>");
    awaitDisabled(2_000);

    RuntimeBridge.reload("<configuration ttl=\"PT0.05S\"></configuration>");
    assertTrue(RuntimeBridge.state(loader).enabled());
    awaitDisabled(2_000);
    assertFalse(RuntimeBridge.state(loader).enabled());
  }

  @Test
  void reloadWithoutTtlDefaultsTo24Hours() {
    publish("<configuration ttl=\"PT0.05S\"></configuration>");
    awaitDisabled(2_000);

    RuntimeBridge.reload("<configuration></configuration>");
    assertTrue(RuntimeBridge.state(loader).enabled());
    assertEquals(Duration.ofHours(24), RuntimeBridge.state(loader).configuration().ttl());
  }

  @Test
  void staleScheduledDisableDoesNotAffectReloadedState() {
    publish("<configuration ttl=\"PT1S\"></configuration>");
    sleep(100);
    RuntimeBridge.reload("<configuration ttl=\"PT10S\"></configuration>");
    assertTrue(RuntimeBridge.state(loader).enabled());
    assertEquals(Duration.ofSeconds(10), RuntimeBridge.state(loader).configuration().ttl());

    sleep(1_200);
    assertTrue(
        RuntimeBridge.state(loader).enabled(),
        "the pre-reload 1s deadline must be invalidated by the reload");
  }

  @Test
  void expiryStrongPushesDisabledStateIntoEnrichmentRuntime() {
    publish("<configuration ttl=\"PT0.05S\"></configuration>");
    awaitDisabled(2_000);

    assertFalse(EnrichmentRuntime.state().enabled());
  }

  @Test
  void scheduledExpiryDoesNotRetainApplicationClassLoader() {
    final WeakReference<ClassLoader> loaderRef = publishTemporaryLoader();

    for (int i = 0; i < 30 && loaderRef.get() != null; i++) {
      System.gc();
      sleep(10);
    }

    assertTrue(loaderRef.get() == null, "scheduled expiry must not retain the application loader");
  }

  private void publish(final String xml) {
    try {
      final CompiledConfiguration configuration =
          new ConfigurationParser().parseXml(xml, loader);
      RuntimeBridge.publish(loader, configuration, xml);
    } catch (final org.otel.agent.config.parser.ConfigurationException exception) {
      throw new AssertionError("test config should be valid", exception);
    }
  }

  private static WeakReference<ClassLoader> publishTemporaryLoader() {
    final ClassLoader temporaryLoader = new URLClassLoader(new URL[0], null);
    RuntimeBridge.publish(
        temporaryLoader,
        new CompiledConfiguration(List.of(), List.of(), Duration.ofHours(24)));
    return new WeakReference<>(temporaryLoader);
  }

  private void awaitDisabled(final long timeoutMillis) {
    final long deadline = System.nanoTime() + Duration.ofMillis(timeoutMillis).toNanos();
    while (RuntimeBridge.state(loader).enabled()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("state still enabled after " + timeoutMillis + "ms");
      }
      sleep(10);
    }
  }

  private static void sleep(final long millis) {
    try {
      Thread.sleep(millis);
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }
}
