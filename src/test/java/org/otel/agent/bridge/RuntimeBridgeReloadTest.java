package org.otel.agent.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.otel.agent.config.model.CompiledConfiguration;
import org.otel.agent.config.parser.ConfigurationParser;

class RuntimeBridgeReloadTest {
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
  void reloadReplacesStateWithNewConfiguration() {
    publishStartupConfig("cars");

    final ReloadResult result = RuntimeBridge.reload(configXml("trucks"));
    assertTrue(result.succeeded());
    assertEquals(1, result.updatedClassloaders());
    assertEquals(1, result.staticRuleCount());
    assertEquals(1, result.exitPointCount());

    final RuntimeState state = RuntimeBridge.state(loader);
    assertTrue(state.enabled());
    assertEquals("trucks", state.configuration().staticRules().getFirst().value());
  }

  @Test
  void reloadFailureLeavesExistingStateUnchanged() {
    publishStartupConfig("cars");

    final ReloadResult result = RuntimeBridge.reload("<invalid>");

    assertFalse(result.succeeded());
    assertEquals(0, result.updatedClassloaders());
    assertEquals(1, result.failures().size());

    final RuntimeState state = RuntimeBridge.state(loader);
    assertTrue(state.enabled());
    assertEquals("cars", state.configuration().staticRules().getFirst().value());
  }

  @Test
  void reloadFailureMessageDoesNotEchoSubmittedXml() {
    publishStartupConfig("cars");

    final ReloadResult result = RuntimeBridge.reload("<configuration><bogus/></configuration>");

    assertFalse(result.succeeded());
    assertEquals("unknown XML element", result.failures().getFirst());
  }

  @Test
  void reloadUpdatesRootClassNames() {
    publishStartupConfig("cars");
    assertTrue(RuntimeBridge.rootClassNames().contains(rootClassName()));

    RuntimeBridge.reload(configXml("trucks"));
    assertTrue(RuntimeBridge.rootClassNames().contains(rootClassName()));
  }

  private void publishStartupConfig(final String team) {
    try {
      final CompiledConfiguration config =
          new ConfigurationParser().parseXml(configXml(team), loader);
      RuntimeBridge.publish(loader, config);
    } catch (final org.otel.agent.config.parser.ConfigurationException exception) {
      throw new AssertionError("test config should be valid", exception);
    }
  }

  private static String configXml(final String team) {
    return "<configuration>"
        + "<static><attribute key=\"team\" value=\""
        + team
        + "\"/></static>"
        + "<dynamic><enrich class=\""
        + rootClassName()
        + "\" method=\"process\">"
        + "<attribute key=\"brand\" path=\"$this.brand\"/>"
        + "</enrich></dynamic>"
        + "</configuration>";
  }

  private static String rootClassName() {
    return "org.otel.agent.bridge.RuntimeBridgeReloadTest$TestModel";
  }

  public static final class TestModel {
    String brand = "cars";

    public void process() {}
  }
}
