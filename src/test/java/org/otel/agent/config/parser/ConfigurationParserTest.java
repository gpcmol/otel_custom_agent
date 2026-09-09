package org.otel.agent.config.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        parser.parseXml(xml, getClass().getClassLoader());

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

  public static final class TestModel {
    public String brand = "cars";

    public void process() {}
  }
}
