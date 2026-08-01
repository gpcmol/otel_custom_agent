package com.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Main {

    private static final int port = 8081;
    private static final ObjectMapper mapper = new ObjectMapper();
    // ponytail: one Garage holds customers (read-only) + parkedCars (mutable list written by park).
    // The agent instruments Garage.park as the configured exit point; the handler delegates every
    // POST to it and the agent enriches the current span once at park's exit.
    private static final Garage demoGarage =
        new Garage(List.of(new Customer("Jan", "Amsterdam")), Collections.synchronizedList(new ArrayList<>()));

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/cars", new CarsHandler());
        server.setExecutor(null);
        server.start();
        System.out.println("Garage running on http://localhost:" + port);
    }

    static class CarsHandler implements HttpHandler {
        @Override
        public void handle(final HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();

            if ("GET".equals(method)) {
                // GET /cars → lijst van alle cars
                demoGarage.getCustomers();
                final String json;
                synchronized (demoGarage.parkedCars()) {
                    json = mapper.writeValueAsString(demoGarage.parkedCars());
                }
                send(exchange, 200, "application/json", json);

            } else if ("POST".equals(method)) {
                // POST /cars → body: {"brand":"..."}
                final String body = new String(exchange.getRequestBody().readAllBytes());
                final Car car = mapper.readValue(body, Car.class);
                // ponytail: Garage.park is the agent's configured exit point. The agent enriches
                // the active span once at its exit. The previous explicit car.getX() calls are
                // gone — they existed only to force per-getter instrumentation; the exit-point
                // model needs a single semantic boundary call instead.
                demoGarage.park(car);
                send(exchange, 201, "application/json", "{\"status\":\"added\"}");

            } else if ("DELETE".equals(method)) {
                // DELETE /cars → leeg de garage (gebruikt door benchmark tussen fasen)
                synchronized (demoGarage.parkedCars()) {
                    demoGarage.parkedCars().clear();
                }
                sendNoContent(exchange);

            } else {
                send(exchange, 405, "text/plain", "Method not allowed");
            }
        }

        private void send(final HttpExchange exchange, final int code, final String type, final String body) throws IOException {
            final byte[] bytes = body.getBytes();
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }

        private void sendNoContent(final HttpExchange exchange) throws IOException {
            exchange.sendResponseHeaders(204, -1);
        }
    }
}