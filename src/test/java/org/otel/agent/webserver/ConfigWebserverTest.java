package org.otel.agent.webserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.otel.agent.bridge.RuntimeBridge;
import org.otel.agent.bridge.RuntimeState;
import org.otel.agent.config.model.CompiledConfiguration;
import org.otel.agent.config.parser.ConfigurationException;
import org.otel.agent.config.parser.ConfigurationParser;

class ConfigWebserverTest {
  private static final int TEST_PORT = findFreePort();
  private static final String BASE_URL = "http://127.0.0.1:" + TEST_PORT;
  private static final String TEST_MODEL_CLASS =
      "org.otel.agent.webserver.ConfigWebserverTest$TestModel";

  private final ClassLoader loader = getClass().getClassLoader();

  @BeforeEach
  void setUp() throws Exception {
    RuntimeBridge.resetForTesting();
    ConfigWebserver.port = TEST_PORT;
    startWithRetry();
  }

  @AfterEach
  void tearDown() {
    ConfigWebserver.stop();
    ConfigWebserver.startupConfigSupplier = () -> System.getenv("OTEL_CUSTOM_AGENT_CONFIG");
    RuntimeBridge.resetForTesting();
  }

  private static void startWithRetry() throws Exception {
    for (int attempt = 0; attempt < 20; attempt++) {
      try {
        ConfigWebserver.start();
        return;
      } catch (final java.net.BindException portNotReleased) {
        Thread.sleep(100);
      }
    }
    ConfigWebserver.start();
  }

  @Test
  void getRootReturnsHtmlUi() throws IOException {
    final HttpResponse response = sendGet("/");

    assertEquals(200, response.status());
    assertEquals("text/html; charset=utf-8", response.contentType());
    assertTrue(response.body().contains("<textarea"));
    assertTrue(response.body().contains("Activate"));
    assertTrue(response.body().contains("Load Original"));
    assertTrue(response.body().contains("Current Configuration"));
    assertEquals(1, countOccurrences(response.body(), "<style>"));
    assertEquals(1, countOccurrences(response.body(), "<script>"));
    assertFalse(response.body().contains("http://") || response.body().contains("https://"));
  }

  @Test
  void getCurrentConfigReturnsActiveXml() throws IOException {
    publishConfig("cars");

    final HttpResponse response = sendGet("/config/current");

    assertEquals(200, response.status());
    assertEquals("text/xml; charset=utf-8", response.contentType());
    assertTrue(response.body().contains("cars"));
  }

  @Test
  void getCurrentConfigReturns404WhenNoActiveConfig() throws IOException {
    final HttpResponse response = sendGet("/config/current");

    assertEquals(404, response.status());
    assertTrue(response.body().contains("No active configuration"));
  }

  @Test
  void getCurrentConfigReflectsReloadedXml() throws IOException {
    publishConfig("cars");
    sendPost("/config", configXml("trucks"));

    final HttpResponse response = sendGet("/config/current");

    assertEquals(200, response.status());
    assertTrue(response.body().contains("trucks"));
    assertFalse(response.body().contains("cars"));
  }

  @Test
  void getOriginalConfigReturnsDecodedStartupXml() throws IOException {
    final String encoded =
        Base64.getEncoder().encodeToString(configXml("cars").getBytes(StandardCharsets.UTF_8));
    ConfigWebserver.startupConfigSupplier = () -> encoded;

    final HttpResponse response = sendGet("/config/original");

    assertEquals(200, response.status());
    assertEquals("text/xml; charset=utf-8", response.contentType());
    assertTrue(response.body().contains("cars"));
  }

  @Test
  void getOriginalConfigReturns404WhenEnvVarAbsent() throws IOException {
    ConfigWebserver.startupConfigSupplier = () -> null;

    final HttpResponse response = sendGet("/config/original");

    assertEquals(404, response.status());
    assertTrue(response.body().contains("No startup configuration"));
  }

  @Test
  void getOriginalConfigReturns500OnInvalidBase64() throws IOException {
    ConfigWebserver.startupConfigSupplier = () -> "!!!invalid-base64!!!";

    final HttpResponse response = sendGet("/config/original");

    assertEquals(500, response.status());
    assertTrue(response.body().contains("Invalid startup configuration"));
  }

  @Test
  void postConfigActivatesNewConfiguration() throws IOException {
    publishConfig("cars");

    final HttpResponse response = sendPost("/config", configXml("trucks"));

    assertEquals(200, response.status());
    assertTrue(response.body().contains("static=1"));
    assertTrue(response.body().contains("exit-points=1"));
    assertTrue(response.body().contains("classloaders=1"));
    assertFalse(response.body().contains("<configuration"));

    final RuntimeState state = RuntimeBridge.state(loader);
    assertTrue(state.enabled());
    assertEquals("trucks", state.configuration().staticRules().getFirst().value());
  }

  @Test
  void postConfigRejectsInvalidXml() throws IOException {
    publishConfig("cars");
    final String originalValue =
        RuntimeBridge.state(loader).configuration().staticRules().getFirst().value();

    final HttpResponse response = sendPost("/config", "<invalid>");

    assertEquals(400, response.status());
    assertTrue(response.body().contains("Configuration rejected"));
    assertFalse(response.body().contains("<configuration"));

    final RuntimeState state = RuntimeBridge.state(loader);
    assertEquals(originalValue, state.configuration().staticRules().getFirst().value());
  }

  @Test
  void postConfigWithNoRegisteredClassloadersReturnsFailure() throws IOException {
    final HttpResponse response = sendPost("/config", configXml("trucks"));

    assertEquals(400, response.status());
    assertTrue(response.body().contains("Configuration rejected"));
  }

  @Test
  void postConfigAcceptsUtf8MultibyteBody() throws IOException {
    publishConfig("cars");
    final String xml = configXml("café→trucks");

    final HttpResponse response = sendPost("/config", xml);

    assertEquals(200, response.status());
    assertEquals(
        "café→trucks",
        RuntimeBridge.state(loader).configuration().staticRules().getFirst().value());
  }

  @Test
  void postConfigWithWrongContentTypeReturns415() throws IOException {
    publishConfig("cars");

    final HttpURLConnection connection =
        (HttpURLConnection) new URL(BASE_URL + "/config").openConnection();
    connection.setRequestMethod("POST");
    connection.setRequestProperty("Content-Type", "application/json");
    connection.setDoOutput(true);
    try (final OutputStream output = connection.getOutputStream()) {
      output.write(configXml("trucks").getBytes(StandardCharsets.UTF_8));
    }

    assertEquals(415, connection.getResponseCode());
  }

  @Test
  void postToRootReturns404() throws IOException {
    final HttpResponse response = sendPost("/", configXml("trucks"));
    assertEquals(404, response.status());
  }

  @Test
  void nonGetToCurrentReturns405() throws IOException {
    final HttpResponse response = sendPost("/config/current", "");
    assertEquals(405, response.status());
  }

  @Test
  void nonPostToConfigReturns405() throws IOException {
    final HttpResponse response = sendGet("/config");
    assertEquals(404, response.status());
  }

  @Test
  void unknownPathReturns404() throws IOException {
    final HttpResponse response = sendGet("/unknown");
    assertEquals(404, response.status());
  }

  @Test
  void portConflictLeavesServerDownWithoutThrowing() throws IOException {
    ConfigWebserver.stop();
    final java.net.ServerSocket blocker =
        new java.net.ServerSocket(TEST_PORT, 0, java.net.InetAddress.getByName("127.0.0.1"));
    try {
      try {
        ConfigWebserver.start();
      } catch (final IOException expected) {
        // Port conflict is expected.
      }
      assertFalse(ConfigWebserver.isRunning());
    } finally {
      blocker.close();
    }
  }

  @Test
  void webserverFailureDoesNotAffectEnrichmentState() throws IOException {
    publishConfig("cars");
    ConfigWebserver.stop();

    final RuntimeState state = RuntimeBridge.state(loader);
    assertTrue(state.enabled());
    assertEquals("cars", state.configuration().staticRules().getFirst().value());
  }

  @Test
  void concurrentReloadsAndStateReadsAreSafe() throws Exception {
    publishConfig("cars");
    final int threads = 10;
    final List<Thread> workers = new ArrayList<>();
    final List<Throwable> errors = new CopyOnWriteArrayList<>();

    for (int i = 0; i < threads; i++) {
      final String team = i % 2 == 0 ? "cars" : "bikes";
      workers.add(
          new Thread(
              () -> {
                try {
                  for (int j = 0; j < 20; j++) {
                    RuntimeBridge.reload(configXml(team));
                    final RuntimeState state = RuntimeBridge.state(loader);
                    if (!state.enabled()) {
                      errors.add(new AssertionError("state not enabled after reload"));
                    }
                  }
                } catch (final Throwable t) {
                  errors.add(t);
                }
              }));
    }
    for (final Thread t : workers) {
      t.start();
    }
    for (final Thread t : workers) {
      t.join();
    }
    assertTrue(errors.isEmpty(), "concurrent errors: " + errors);
  }

  @Test
  void partialReloadFailureLeavesSuccessfulLoaderUpdated() throws Exception {
    publishConfig("cars");
    final String originalValue =
        RuntimeBridge.state(loader).configuration().staticRules().getFirst().value();

    final ClassLoader bogusLoader = new ClassLoader(loader) {};
    try {
      final CompiledConfiguration bogusConfig =
          new ConfigurationParser().parseXml(configXml("trucks"), bogusLoader);
      RuntimeBridge.publish(bogusLoader, bogusConfig);
    } catch (final ConfigurationException exception) {
      throw new AssertionError(exception);
    }

    final HttpResponse response = sendPost("/config", "<invalid>");
    assertEquals(400, response.status());
    assertTrue(response.body().contains("Configuration rejected"));

    assertEquals(
        originalValue,
        RuntimeBridge.state(loader).configuration().staticRules().getFirst().value());
  }

  private void publishConfig(final String team) {
    try {
      final String xml = configXml(team);
      final CompiledConfiguration config = new ConfigurationParser().parseXml(xml, loader);
      RuntimeBridge.publish(loader, config, xml);
    } catch (final ConfigurationException exception) {
      throw new AssertionError("test config should be valid", exception);
    }
  }

  private static String configXml(final String team) {
    return "<configuration>"
        + "<static><attribute key=\"team\" value=\""
        + team
        + "\"/></static>"
        + "<dynamic><enrich class=\""
        + TEST_MODEL_CLASS
        + "\" method=\"process\">"
        + "<attribute key=\"brand\" path=\"$this.brand\"/>"
        + "</enrich></dynamic>"
        + "</configuration>";
  }

  private static HttpResponse sendGet(final String path) throws IOException {
    final HttpURLConnection connection =
        (HttpURLConnection) new URL(BASE_URL + path).openConnection();
    connection.setRequestMethod("GET");
    connection.setConnectTimeout(5000);
    connection.setReadTimeout(5000);
    return readResponse(connection);
  }

  private static HttpResponse sendPost(final String path, final String body) throws IOException {
    final HttpURLConnection connection =
        (HttpURLConnection) new URL(BASE_URL + path).openConnection();
    connection.setRequestMethod("POST");
    connection.setRequestProperty("Content-Type", "text/xml");
    connection.setDoOutput(true);
    connection.setConnectTimeout(5000);
    connection.setReadTimeout(5000);
    try (final OutputStream output = connection.getOutputStream()) {
      output.write(body.getBytes(StandardCharsets.UTF_8));
    }
    return readResponse(connection);
  }

  private static HttpResponse readResponse(final HttpURLConnection connection) throws IOException {
    final int status = connection.getResponseCode();
    final String contentType = connection.getContentType();
    final InputStream stream =
        status >= 400 ? connection.getErrorStream() : connection.getInputStream();
    final String body;
    if (stream != null) {
      try (stream) {
        body = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      }
    } else {
      body = "";
    }
    return new HttpResponse(status, contentType, body);
  }

  private static int countOccurrences(final String text, final String substring) {
    int count = 0;
    int index = 0;
    while ((index = text.indexOf(substring, index)) >= 0) {
      count++;
      index += substring.length();
    }
    return count;
  }

  private static int findFreePort() {
    try (final java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (final IOException exception) {
      throw new AssertionError("could not find a free port", exception);
    }
  }

  private record HttpResponse(int status, String contentType, String body) {}

  public static final class TestModel {
    String brand = "cars";

    public void process() {}
  }
}
