package com.example;

public record Customer(String name, String city) {
    public String getName() {
        return name;
    }

    public String getCity() {
        return city;
    }
}
