package org.otel.agent.config.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.otel.agent.config.model.CompiledConfiguration;

class ConfigurationParserRawXmlTest {
  private final ConfigurationParser parser = new ConfigurationParser();

  @Test
  void compilesValidXmlWithStaticAndDynamicRules() throws Exception {
    final String xml =
        """
            <?xml version="1.0" encoding="UTF-8" standalone="no"?>
            <configuration>
                <static>
                    <attribute key="team" value="cars"/>
                </static>
                <dynamic>
                    <enrich class="org.otel.agent.config.parser.ConfigurationParserRawXmlTest$TestModel"
                            method="process">
                        <attribute key="brand" path="$this.brand"/>
                    </enrich>
                </dynamic>
            </configuration>
            """;

    final CompiledConfiguration configuration = parser.parseXml(xml, getClass().getClassLoader());

    assertEquals(1, configuration.staticRules().size());
    assertEquals("cars", configuration.staticRules().getFirst().value());
    assertEquals(1, configuration.exitPoints().size());
    assertEquals("brand", configuration.exitPoints().getFirst().rules().getFirst().key());
  }

  @Test
  void acceptsEmptyConfiguration() throws Exception {
    final CompiledConfiguration configuration =
        parser.parseXml("<configuration/>", getClass().getClassLoader());

    assertTrue(configuration.staticRules().isEmpty());
    assertTrue(configuration.exitPoints().isEmpty());
  }

  @Test
  void rejectsBlankXml() {
    assertThrows(
        ConfigurationException.class, () -> parser.parseXml("   ", getClass().getClassLoader()));
  }

  @Test
  void rejectsNullXml() {
    assertThrows(
        ConfigurationException.class, () -> parser.parseXml(null, getClass().getClassLoader()));
  }

  @Test
  void rejectsUnknownElement() {
    assertThrows(
        ConfigurationException.class,
        () ->
            parser.parseXml(
                "<configuration><bogus/></configuration>", getClass().getClassLoader()));
  }

  @Test
  void rejectsUnknownAttribute() {
    assertThrows(
        ConfigurationException.class,
        () ->
            parser.parseXml(
                "<configuration><static><attribute key='x' value='y' extra='z'/></static></configuration>",
                getClass().getClassLoader()));
  }

  @Test
  void rejectsDuplicateKeysAcrossSections() {
    assertThrows(
        ConfigurationException.class,
        () ->
            parser.parseXml(
                "<configuration><static><attribute key='dup' value='a'/></static>"
                    + "<dynamic><enrich class='org.otel.agent.config.parser.ConfigurationParserRawXmlTest$TestModel' method='process'>"
                    + "<attribute key='dup' path='$this.brand'/></enrich></dynamic></configuration>",
                getClass().getClassLoader()));
  }

  @Test
  void rejectsInvalidPath() {
    assertThrows(
        ConfigurationException.class,
        () ->
            parser.parseXml(
                "<configuration><dynamic><enrich class='org.otel.agent.config.parser.ConfigurationParserRawXmlTest$TestModel' method='process'>"
                    + "<attribute key='bad' path='no_dot_path'/></enrich></dynamic></configuration>",
                getClass().getClassLoader()));
  }

  @Test
  void rejectsUnresolvableRootClass() {
    assertThrows(
        ConfigurationException.class,
        () ->
            parser.parseXml(
                "<configuration><dynamic><enrich class='com.nonexistent.Foo' method='process'>"
                    + "<attribute key='bad' path='$this.bar'/></enrich></dynamic></configuration>",
                getClass().getClassLoader()));
  }

  @Test
  void rejectsExternalEntity() {
    final String xml =
        """
            <?xml version="1.0" encoding="UTF-8" standalone="no"?>
            <!DOCTYPE configuration [
              <!ENTITY xxe SYSTEM "file:///etc/passwd">
            ]>
            <configuration>&xxe;</configuration>
            """;
    assertThrows(
        ConfigurationException.class, () -> parser.parseXml(xml, getClass().getClassLoader()));
  }

  @Test
  void producesImmutableConfiguration() throws Exception {
    final CompiledConfiguration configuration =
        parser.parseXml("<configuration/>", getClass().getClassLoader());

    assertThrows(UnsupportedOperationException.class, () -> configuration.staticRules().add(null));
    assertThrows(UnsupportedOperationException.class, () -> configuration.exitPoints().add(null));
  }

  public static final class TestModel {
    String brand = "cars";

    public void process() {}
  }
}
