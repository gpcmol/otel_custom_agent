package com.example;

public record Passenger(String name) {
    public String getName() {
        return name;
    }
}
