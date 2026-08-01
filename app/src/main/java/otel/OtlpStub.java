package otel;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class OtlpStub {

    public static void main(String[] args) throws Exception {
        String host = System.getenv().getOrDefault("OTLP_STUB_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("OTLP_STUB_PORT", "4317"));
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);

        server.createContext("/", OtlpStub::handle);

        server.start();

        System.out.println("Listening on http://" + host + ":" + port);
    }

    private static void handle(HttpExchange exchange) throws IOException {

        byte[] body = exchange.getRequestBody().readAllBytes();

        if (body.length == 0) {
            // Health-check or empty request; no span data to parse.
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }

        ExportTraceServiceRequest exportTraceServiceRequest = ExportTraceServiceRequest.parseFrom(body);

        System.out.println(exportTraceServiceRequest);

        appendToFile("telemetry.log", exportTraceServiceRequest.toString());

        System.out.println("Body length: " + body.length);

        exchange.sendResponseHeaders(200, -1);
        exchange.close();
    }

    public static void appendToFile(String filename, String content) throws IOException {
        Path path = Path.of(filename);

        Files.writeString(
                path,
                content + System.lineSeparator(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
    }
}