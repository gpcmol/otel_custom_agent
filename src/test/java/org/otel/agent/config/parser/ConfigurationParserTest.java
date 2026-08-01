package org.otel.agent.config.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.otel.agent.config.model.CompiledConfiguration;

class ConfigurationParserTest {
  private final ConfigurationParser parser = new ConfigurationParser();

  @Test
  void compilesStaticAndDynamicRules() throws Exception {
    final String xml =
        """
        <?xml version="1.0" encoding="UTF-8" standalone="no"?>
        <configuration>
            <static>
                <attribute key="team" value="cars"/>
            </static>
            <dynamic>
                <enrich class="org.otel.agent.config.parser.ConfigurationParserTest$TestModel"
                        method="process">
                    <attribute key="brand" path="$this.brand"/>
                </enrich>
            </dynamic>
        </configuration>
        """;

    final CompiledConfiguration configuration =
        parser.parse(encoded(xml), getClass().getClassLoader());

    assertEquals("cars", configuration.staticRules().getFirst().value());
    assertEquals(
        "brand",
        configuration
            .exitPoints()
            .getFirst()
            .rules()
            .getFirst()
            .segments()
            .getFirst()
            .toString()
            .replace("PropertySegment[propertyName=", "")
            .replace("]", ""));
  }

  @Test
  void rejectsInvalidUtf8() {
    final String invalid =
        Base64.getEncoder().encodeToString(new byte[] {(byte) 0xc3, (byte) 0x28});
    assertThrows(
        ConfigurationException.class, () -> parser.parse(invalid, getClass().getClassLoader()));
  }

  @Test
  void missingConfigurationIsEmpty() throws Exception {
    final CompiledConfiguration configuration = parser.parse(null, getClass().getClassLoader());
    assertEquals(0, configuration.staticRules().size());
    assertEquals(0, configuration.exitPoints().size());
  }

  private static String encoded(final String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  public static final class TestModel {
    public String brand = "cars";

    public void process() {}
  }
}
