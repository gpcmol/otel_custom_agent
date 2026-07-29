package org.otel.agent.webserver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import org.otel.agent.bridge.ReloadResult;
import org.otel.agent.bridge.RuntimeBridge;
import org.otel.agent.config.parser.ConfigurationException;
import org.otel.agent.config.parser.ConfigurationParser;

public final class ConfigWebserver {
  static int port = Integer.getInteger("otel.config.webserver.port", 14317);
  private static final String LOOPBACK = "127.0.0.1";

  static volatile Supplier<String> startupConfigSupplier =
      () -> System.getenv("OTEL_CUSTOM_AGENT_CONFIG");

  private static ServerSocket serverSocket;
  private static Thread acceptThread;

  private ConfigWebserver() {}

  public static synchronized void start() throws IOException {
    if (serverSocket != null && !serverSocket.isClosed()) return;
    final ServerSocket socket = new ServerSocket();
    socket.bind(new InetSocketAddress(LOOPBACK, port));
    serverSocket = socket;
    acceptThread = new Thread(ConfigWebserver::acceptLoop, "otel-config-webserver");
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
    while (!Thread.currentThread().isInterrupted()) {
      try (final Socket client = serverSocket.accept()) {
        handleClient(client);
      } catch (final IOException ignored) {
        return;
      }
    }
  }

  private static void handleClient(final Socket client) throws IOException {
    client.setSoTimeout(5000);
    final BufferedReader reader =
        new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
    final String requestLine = reader.readLine();
    if (requestLine == null) return;

    final String[] requestParts = requestLine.split(" ", 3);
    if (requestParts.length < 2) {
      sendResponse(client, 400, "text/plain", "Bad Request");
      return;
    }
    final String method = requestParts[0];
    final String path = requestParts[1];

    final int contentLength = readHeaders(reader);
    final String body = readBody(reader, contentLength);

    route(client, method, path, body);
  }

  private static int readHeaders(final BufferedReader reader) throws IOException {
    int contentLength = 0;
    String line;
    while ((line = reader.readLine()) != null && !line.isEmpty()) {
      final String lower = line.toLowerCase();
      if (lower.startsWith("content-length:")) {
        try {
          contentLength = Integer.parseInt(lower.substring(15).trim());
        } catch (final NumberFormatException ignored) {
        }
      }
    }
    return contentLength;
  }

  private static String readBody(final BufferedReader reader, final int contentLength)
      throws IOException {
    if (contentLength <= 0) return "";
    final char[] buffer = new char[contentLength];
    int read = 0;
    while (read < contentLength) {
      final int count = reader.read(buffer, read, contentLength - read);
      if (count < 0) break;
      read += count;
    }
    return new String(buffer, 0, read);
  }

  private static void route(
      final Socket client, final String method, final String path, final String body)
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
      handleReload(client, body);
    } else if ("POST".equals(method) && "/config".startsWith(path)) {
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
      final byte[] decoded = ConfigurationParser.decode(encoded);
      sendResponse(client, 200, "text/xml; charset=utf-8", new String(decoded, StandardCharsets.UTF_8));
    } catch (final ConfigurationException exception) {
      sendResponse(client, 500, "text/plain", "Invalid startup configuration: " + exception.getMessage());
    }
  }

  private static void handleReload(final Socket client, final String body) throws IOException {
    final ReloadResult result = RuntimeBridge.reload(body);
    if (result.updatedClassloaders() > 0) {
      final String message =
          String.format(
              "Activated: static=%d dynamic=%d classloaders=%d%s",
              result.staticRuleCount(),
              result.dynamicRuleCount(),
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
    header.append("HTTP/1.1 ").append(status).append(" ").append(reasonPhrase(status)).append("\r\n");
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
      case 500 -> "Internal Server Error";
      default -> "Unknown";
    };
  }
}
