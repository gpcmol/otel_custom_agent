package com.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class Main {

    private static final int port = 8081;
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final List<Car> garage = new CopyOnWriteArrayList<>();
    private static final Garage demoGarage = new Garage(List.of(new Customer("Jan", "Amsterdam")));

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
                final String json = mapper.writeValueAsString(garage);
                send(exchange, 200, "application/json", json);

            } else if ("POST".equals(method)) {
                // POST /cars → body: {"brand":"..."}
                final String body = new String(exchange.getRequestBody().readAllBytes());
                final Car car = mapper.readValue(body, Car.class);
                car.getBrand();
                car.getPassengers();
                garage.add(car);
                send(exchange, 201, "application/json", "{\"status\":\"added\"}");

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
    }
}
