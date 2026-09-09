package org.otel.agent.config.parser;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ConfigurationValidationTest {
  @Test
  void rejectsDuplicateSections() {
    assertThrows(
        ConfigurationException.class,
        () ->
            new ConfigurationParser()
                .parseXml(
                    "<configuration><static/><static/></configuration>",
                    getClass().getClassLoader()));
  }

  @Test
  void rejectsUnknownAttributes() {
    assertThrows(
        ConfigurationException.class,
        () ->
            new ConfigurationParser()
                .parseXml(
                    "<configuration><static><attribute key='x' value='y' extra='z'/></static></configuration>",
                    getClass().getClassLoader()));
  }

  @Test
  void rejectsEnrichWithoutClass() {
    assertThrows(
        ConfigurationException.class,
        () ->
            new ConfigurationParser()
                .parseXml(
                    "<configuration><dynamic><enrich method=\"process\">"
                            + "<attribute key=\"x\" path=\"$this.x\"/></enrich></dynamic></configuration>",
                    getClass().getClassLoader()));
  }

  @Test
  void rejectsEnrichWithoutMethod() {
    assertThrows(
        ConfigurationException.class,
        () ->
            new ConfigurationParser()
                .parseXml(
                    "<configuration><dynamic><enrich class=\"java.lang.Object\">"
                            + "<attribute key=\"x\" path=\"$this.x\"/></enrich></dynamic></configuration>",
                    getClass().getClassLoader()));
  }

  @Test
  void rejectsDuplicateExitPoints() {
    assertThrows(
        ConfigurationException.class,
        () ->
            new ConfigurationParser()
                .parseXml(
                    "<configuration><dynamic>"
                            + "<enrich class=\"java.lang.Object\" method=\"toString\">"
                            + "<attribute key=\"x\" path=\"$this.x\"/></enrich>"
                            + "<enrich class=\"java.lang.Object\" method=\"toString\">"
                            + "<attribute key=\"y\" path=\"$this.y\"/></enrich>"
                            + "</dynamic></configuration>",
                    getClass().getClassLoader()));
  }

  @Test
  void rejectsUnresolvableExitPointClass() {
    assertThrows(
        ConfigurationException.class,
        () ->
            new ConfigurationParser()
                .parseXml(
                    "<configuration><dynamic><enrich class=\"com.nonexistent.Foo\" method=\"process\">"
                            + "<attribute key=\"x\" path=\"$this.x\"/></enrich></dynamic></configuration>",
                    getClass().getClassLoader()));
  }

  @Test
  void rejectsExitPointMethodNotFound() {
    assertThrows(
        ConfigurationException.class,
        () ->
            new ConfigurationParser()
                .parseXml(
                    "<configuration><dynamic><enrich class=\"java.lang.Object\" method=\"noSuchMethod\">"
                            + "<attribute key=\"x\" path=\"$this.x\"/></enrich></dynamic></configuration>",
                    getClass().getClassLoader()));
  }

  @Test
  void rejectsLegacyFlatDynamicForm() {
    assertThrows(
        ConfigurationException.class,
        () ->
            new ConfigurationParser()
                .parseXml(
                    "<configuration><dynamic><attribute key=\"x\" path=\"java.lang.Object.toString\"/>"
                            + "</dynamic></configuration>",
                    getClass().getClassLoader()));
  }

  @Test
  void rejectsUnknownRootPrefix() {
    assertThrows(
        ConfigurationException.class,
        () ->
            new ConfigurationParser()
                .parseXml(
                    "<configuration><dynamic><enrich class=\"java.lang.Object\" method=\"toString\">"
                            + "<attribute key=\"x\" path=\"$receiver.x\"/></enrich></dynamic></configuration>",
                    getClass().getClassLoader()));
  }

  @Test
  void rejectsArgWithoutIndex() {
    assertThrows(
        ConfigurationException.class,
        () ->
            new ConfigurationParser()
                .parseXml(
                    "<configuration><dynamic><enrich class=\"java.lang.Object\" method=\"toString\">"
                            + "<attribute key=\"x\" path=\"$arg.x\"/></enrich></dynamic></configuration>",
                    getClass().getClassLoader()));
  }

  @Test
  void rejectsArgIndexOutOfRange() {
    assertThrows(
        ConfigurationException.class,
        () ->
            new ConfigurationParser()
                .parseXml(
                    "<configuration><dynamic><enrich class=\"java.lang.Object\" method=\"toString\">"
                            + "<attribute key=\"x\" path=\"$arg999.x\"/></enrich></dynamic></configuration>",
                    getClass().getClassLoader()));
  }

}
