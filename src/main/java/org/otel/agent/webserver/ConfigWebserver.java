package org.otel.agent.webserver;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import org.otel.agent.bridge.ReloadResult;
import org.otel.agent.bridge.RuntimeBridge;
import org.otel.agent.config.parser.ConfigurationException;
import org.otel.agent.utils.Base64Util;

/**
 * Embedded HTTP server for runtime configuration inspection and reload.
 *
 * <p>Binds to {@code 127.0.0.1:14317} (loopback only) and serves four endpoints: an HTML config
 * UI at {@code GET /}, the active XML at {@code GET /config/current}, the original startup XML
 * at {@code GET /config/original}, and a reload endpoint at {@code POST /config}.
 *
 * <p>Uses a raw {@link java.net.ServerSocket} with minimal HTTP/1.1 parsing instead of
 * {@link com.sun.net.httpserver.HttpServer}. The OpenTelemetry Java agent ships a
 * {@code java-httpserver} instrumentation module that intercepts
 * {@code com.sun.net.httpserver.HttpServer} at bootstrap-classloader initialization time and wraps
 * its handler execution. Because the agent extension class loader and the system class loader
 * diverge, handler threads spawned by the instrumented {@code HttpServer} cannot reliably cross
 * back into the extension class loader, causing handler calls to hang. A raw {@code ServerSocket}
 * is not instrumented by {@code java-httpserver} and keeps all accept/parse/route logic within the
 * extension class loader, avoiding the cross-loader boundary entirely. This choice also adds no
 * dependencies beyond the JDK.
 */
public final class ConfigWebserver {
  // ponytail: test hook; the spec port is 14317
  static int port = Integer.getInteger("otel.config.webserver.port", 14317);
  private static final String LOOPBACK = "127.0.0.1";
  private static final int MAX_BODY_BYTES = 1_048_576;

  static volatile Supplier<String> startupConfigSupplier =
      () -> System.getenv("OTEL_CUSTOM_AGENT_CONFIG");

  private static ServerSocket serverSocket;
    private ConfigWebserver() {}

  public static synchronized void start() throws IOException {
    if (serverSocket != null && !serverSocket.isClosed()) return;
    final ServerSocket socket = new ServerSocket();
    socket.bind(new InetSocketAddress(LOOPBACK, port));
    serverSocket = socket;
    final Thread acceptThread = new Thread(ConfigWebserver::acceptLoop, "otel-config-webserver");
    acceptThread.setDaemon(true);
    acceptThread.start();
  }

  public static synchronized void stop() {
    if (serverSocket != null) {
      try {
        serverSocket.close();
      } catch (final IOException ignored) {
      }
      serverSocket = null;
    }
  }

  public static synchronized boolean isRunning() {
    return serverSocket != null && !serverSocket.isClosed();
  }

  private static void acceptLoop() {
    while (true) {
      try (final Socket client = serverSocket.accept()) {
        handleClient(client);
      } catch (final IOException ignored) {
        if (serverSocket == null || serverSocket.isClosed()) return;
      }
    }
  }

  private static void handleClient(final Socket client) throws IOException {
    client.setSoTimeout(5000);
    final InputStream input = new BufferedInputStream(client.getInputStream());
    final String requestLine = readLine(input);
    if (requestLine == null) return;

    final String[] requestParts = requestLine.split(" ", 3);
    if (requestParts.length < 2) {
      sendResponse(client, 400, "text/plain", "Bad Request");
      return;
    }
    final String method = requestParts[0];
    final String path = requestParts[1];

    int contentLength = 0;
    String contentType = null;
    String line;
    while ((line = readLine(input)) != null && !line.isEmpty()) {
      final String lower = line.toLowerCase();
      if (lower.startsWith("content-length:")) {
        try {
          contentLength = Integer.parseInt(lower.substring(15).trim());
        } catch (final NumberFormatException ignored) {
        }
      } else if (lower.startsWith("content-type:")) {
        contentType = lower.substring(13).trim();
      }
    }
    if (contentLength > MAX_BODY_BYTES) {
      sendResponse(client, 400, "text/plain", "Request body too large");
      return;
    }
    final String body = readBody(input, contentLength);

    route(client, method, path, contentType, body);
  }

  private static String readLine(final InputStream input) throws IOException {
    final StringBuilder line = new StringBuilder();
    int current;
    while ((current = input.read()) >= 0) {
      if (current == '\n') {
        if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
          line.setLength(line.length() - 1);
        }
        return line.toString();
      }
      line.append((char) current);
    }
    return line.isEmpty() ? null : line.toString();
  }

  private static String readBody(final InputStream input, final int contentLength)
      throws IOException {
    if (contentLength <= 0) return "";
    final byte[] buffer = new byte[contentLength];
    int read = 0;
    while (read < contentLength) {
      final int count = input.read(buffer, read, contentLength - read);
      if (count < 0) break;
      read += count;
    }
    return new String(buffer, 0, read, StandardCharsets.UTF_8);
  }

  private static void route(
      final Socket client,
      final String method,
      final String path,
      final String contentType,
      final String body)
      throws IOException {
    if ("GET".equals(method) && "/".equals(path)) {
      sendResponse(client, 200, "text/html; charset=utf-8", ConfigPage.html());
    } else if ("GET".equals(method) && "/config/current".equals(path)) {
      final String xml = RuntimeBridge.activeXml();
      if (xml == null) {
        sendResponse(client, 404, "text/plain", "No active configuration");
      } else {
        sendResponse(client, 200, "text/xml; charset=utf-8", xml);
      }
    } else if ("GET".equals(method) && "/config/original".equals(path)) {
      handleOriginal(client);
    } else if ("POST".equals(method) && "/config".equals(path)) {
      if (contentType != null && !contentType.startsWith("text/xml")) {
        sendResponse(client, 415, "text/plain", "Unsupported Media Type");
      } else {
        handleReload(client, body);
      }
    } else if ("POST".equals(method) && path.startsWith("/config")) {
      sendResponse(client, 405, "text/plain", "Method Not Allowed");
    } else if (path.startsWith("/config/current") || path.startsWith("/config/original")) {
      sendResponse(client, 405, "text/plain", "Method Not Allowed");
    } else {
      sendResponse(client, 404, "text/plain", "Not Found");
    }
  }

  private static void handleOriginal(final Socket client) throws IOException {
    final String encoded = startupConfigSupplier.get();
    if (encoded == null) {
      sendResponse(client, 404, "text/plain", "No startup configuration was set");
      return;
    }
    try {
      final byte[] decoded = Base64Util.decode(encoded);
      sendResponse(
          client, 200, "text/xml; charset=utf-8", new String(decoded, StandardCharsets.UTF_8));
    } catch (final ConfigurationException exception) {
      sendResponse(
          client, 500, "text/plain", "Invalid startup configuration: " + exception.getMessage());
    }
  }

  private static void handleReload(final Socket client, final String body) throws IOException {
    final ReloadResult result = RuntimeBridge.reload(body);
    if (result.updatedClassloaders() > 0) {
      final String message =
          String.format(
              "Activated: static=%d exit-points=%d classloaders=%d%s",
              result.staticRuleCount(),
              result.exitPointCount(),
              result.updatedClassloaders(),
              result.failures().isEmpty()
                  ? ""
                  : " (skipped " + result.failures().size() + " loader(s))");
      sendResponse(client, 200, "text/plain", message);
    } else {
      final String message =
          String.format("Configuration rejected: %s", String.join("; ", result.failures()));
      sendResponse(client, 400, "text/plain", message);
    }
  }

  private static void sendResponse(
      final Socket client, final int status, final String contentType, final String body)
      throws IOException {
    final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    final StringBuilder header = new StringBuilder();
    header
        .append("HTTP/1.1 ")
        .append(status)
        .append(" ")
        .append(reasonPhrase(status))
        .append("\r\n");
    header.append("Content-Type: ").append(contentType).append("\r\n");
    header.append("Content-Length: ").append(bytes.length).append("\r\n");
    header.append("Connection: close\r\n");
    header.append("\r\n");
    final OutputStream output = client.getOutputStream();
    output.write(header.toString().getBytes(StandardCharsets.UTF_8));
    output.write(bytes);
    output.flush();
  }

  private static String reasonPhrase(final int status) {
    return switch (status) {
      case 200 -> "OK";
      case 400 -> "Bad Request";
      case 404 -> "Not Found";
      case 405 -> "Method Not Allowed";
      case 415 -> "Unsupported Media Type";
      case 500 -> "Internal Server Error";
      default -> "Unknown";
    };
  }
}
