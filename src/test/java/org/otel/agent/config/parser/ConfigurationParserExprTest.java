package org.otel.agent.config.parser;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.otel.agent.config.model.CompiledConfiguration;

class ConfigurationParserExprTest {

  private final ConfigurationParser parser = new ConfigurationParser();

  @Test
  void validExprProducesNonNullCondition() throws Exception {
    final String xml =
        """
        <?xml version="1.0" encoding="UTF-8" standalone="no"?>
        <configuration>
            <dynamic>
                <enrich class="org.otel.agent.config.parser.ConfigurationParserExprTest$Fixture"
                        method="process" expr="$this.brand == 'BMW'">
                    <attribute key="brand" path="$this.brand"/>
                </enrich>
            </dynamic>
        </configuration>
        """;
    final CompiledConfiguration config = parser.parseXml(xml, getClass().getClassLoader());
    assertNotNull(config.exitPoints().getFirst().condition());
  }

  @Test
  void syntacticallyInvalidExprProducesNullCondition() throws Exception {
    final String xml =
        """
        <?xml version="1.0" encoding="UTF-8" standalone="no"?>
        <configuration>
            <dynamic>
                <enrich class="org.otel.agent.config.parser.ConfigurationParserExprTest$Fixture"
                        method="process" expr="$this.brand ==">
                    <attribute key="brand" path="$this.brand"/>
                </enrich>
            </dynamic>
        </configuration>
        """;
    final CompiledConfiguration config = parser.parseXml(xml, getClass().getClassLoader());
    assertNull(config.exitPoints().getFirst().condition());
  }

  @Test
  void typeMismatchExprProducesNullCondition() throws Exception {
    final String xml =
        """
        <?xml version="1.0" encoding="UTF-8" standalone="no"?>
        <configuration>
            <dynamic>
                <enrich class="org.otel.agent.config.parser.ConfigurationParserExprTest$Fixture"
                        method="process" expr="$this.brand == 3">
                    <attribute key="brand" path="$this.brand"/>
                </enrich>
            </dynamic>
        </configuration>
        """;
    final CompiledConfiguration config = parser.parseXml(xml, getClass().getClassLoader());
    assertNull(config.exitPoints().getFirst().condition());
  }

  @Test
  void wordOperatorsInExprWithoutEscapingProduceNonNullCondition() throws Exception {
    // and/gt/not are word operators: the expr fits verbatim in the XML attribute, no
    // entity escaping (no &amp;&amp;, no &lt;).
    final String xml =
        """
        <?xml version="1.0" encoding="UTF-8" standalone="no"?>
        <configuration>
            <dynamic>
                <enrich class="org.otel.agent.config.parser.ConfigurationParserExprTest$Fixture"
                        method="process"
                        expr="$this.brand == 'BMW' and $this.mileage gt 0 and not ($this.brand == 'Audi')">
                    <attribute key="brand" path="$this.brand"/>
                </enrich>
            </dynamic>
        </configuration>
        """;
    final CompiledConfiguration config = parser.parseXml(xml, getClass().getClassLoader());
    assertNotNull(config.exitPoints().getFirst().condition());
  }

  @Test
  void absentExprProducesNullCondition() throws Exception {
    final String xml =
        """
        <?xml version="1.0" encoding="UTF-8" standalone="no"?>
        <configuration>
            <dynamic>
                <enrich class="org.otel.agent.config.parser.ConfigurationParserExprTest$Fixture"
                        method="process">
                    <attribute key="brand" path="$this.brand"/>
                </enrich>
            </dynamic>
        </configuration>
        """;
    final CompiledConfiguration config = parser.parseXml(xml, getClass().getClassLoader());
    assertNull(config.exitPoints().getFirst().condition());
  }

  @Test
  void duplicateEnrichBlocksStillRejected() {
    final String xml =
        """
        <?xml version="1.0" encoding="UTF-8" standalone="no"?>
        <configuration>
            <dynamic>
                <enrich class="org.otel.agent.config.parser.ConfigurationParserExprTest$Fixture"
                        method="process" expr="$this.brand == 'BMW'">
                    <attribute key="brand" path="$this.brand"/>
                </enrich>
                <enrich class="org.otel.agent.config.parser.ConfigurationParserExprTest$Fixture"
                        method="process" expr="$this.brand == 'Audi'">
                    <attribute key="brand2" path="$this.brand"/>
                </enrich>
            </dynamic>
        </configuration>
        """;
    assertThrows(ConfigurationException.class, () -> parser.parseXml(xml, getClass().getClassLoader()));
  }

  public static final class Fixture {
    public String brand = "BMW";
    public long mileage = 12_000L;

    public String getBrand() {
      return brand;
    }

    public long getMileage() {
      return mileage;
    }

    public void process() {}
  }
}
