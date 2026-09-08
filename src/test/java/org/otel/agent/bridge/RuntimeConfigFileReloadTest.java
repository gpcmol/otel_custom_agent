package org.otel.agent.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RuntimeConfigFileReloadTest {
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
  void fileConfigurationTakesPrecedenceOverEnvironment() throws Exception {
    final Path file = Files.createTempFile("otel-config", ".xml");
    Files.writeString(file, configXml("file"));

    RuntimeBridge.initializeForTesting(loader, file.toString(), "5", encoded("environment"));

    assertEquals("file", activeTeam());
  }

  @Test
  void environmentConfigurationRemainsFallback() {
    RuntimeBridge.initializeForTesting(loader, null, null, encoded("environment"));

    assertEquals("environment", activeTeam());
  }

  @Test
  void noConfigurationPreservesDisabledState() {
    RuntimeBridge.initializeForTesting(loader, null, null, null);

    assertFalse(RuntimeBridge.state(loader).enabled());
  }

  @Test
  void intervalDefaultsAndRejectsInvalidValues() {
    assertEquals(5, RuntimeBridge.reloadIntervalForTesting(null));
    assertEquals(5, RuntimeBridge.reloadIntervalForTesting(""));
    assertEquals(5, RuntimeBridge.reloadIntervalForTesting("0"));
    assertEquals(5, RuntimeBridge.reloadIntervalForTesting("not-a-number"));
    assertEquals(2, RuntimeBridge.reloadIntervalForTesting("2"));
  }

  @Test
  void validFileChangeIsAppliedWithoutRestart() throws Exception {
    final Path file = Files.createTempFile("otel-config", ".xml");
    Files.writeString(file, configXml("before"));
    RuntimeBridge.initializeForTesting(loader, file.toString(), "1", null);

    Files.writeString(file, configXml("after"));

    awaitTeam("after");
    assertTrue(RuntimeBridge.state(loader).enabled());
  }

  @Test
  void invalidFileChangeKeepsLastValidConfigurationAndRetries() throws Exception {
    final Path file = Files.createTempFile("otel-config", ".xml");
    Files.writeString(file, configXml("before"));
    RuntimeBridge.initializeForTesting(loader, file.toString(), "1", null);

    Files.writeString(file, "<invalid>");
    Thread.sleep(1_200);
    assertEquals("before", activeTeam());

    Files.writeString(file, configXml("after"));
    awaitTeam("after");
  }

  private String activeTeam() {
    return RuntimeBridge.state(loader).configuration().staticRules().getFirst().value();
  }

  private void awaitTeam(final String expected) throws InterruptedException {
    for (int attempt = 0; attempt < 20; attempt++) {
      if (expected.equals(activeTeam())) return;
      Thread.sleep(250);
    }
    assertEquals(expected, activeTeam());
  }

  private static String encoded(final String team) {
    return Base64.getEncoder()
        .encodeToString(configXml(team).getBytes(StandardCharsets.UTF_8));
  }

  private static String configXml(final String team) {
    return "<configuration>"
        + "<static><attribute key=\"team\" value=\""
        + team
        + "\"/></static>"
        + "</configuration>";
  }
}
