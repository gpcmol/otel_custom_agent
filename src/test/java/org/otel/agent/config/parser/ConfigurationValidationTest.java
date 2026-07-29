package org.otel.agent.config.parser;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class ConfigurationValidationTest {
  @Test
  void rejectsDuplicateSections() {
    assertThrows(
        ConfigurationException.class,
        () ->
            new ConfigurationParser()
                .parse(
                    encoded("<configuration><static/><static/></configuration>"),
                    getClass().getClassLoader()));
  }

  @Test
  void rejectsUnknownAttributes() {
    assertThrows(
        ConfigurationException.class,
        () ->
            new ConfigurationParser()
                .parse(
                    encoded(
                        "<configuration><static><attribute key='x' value='y' extra='z'/></static></configuration>"),
                    getClass().getClassLoader()));
  }

  private static String encoded(final String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
