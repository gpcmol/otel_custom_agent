package com.example;

import java.util.List;

public record Car(String brand, List<Passenger> passengers) {

    public String getBrand() {
        return brand;
    }

    public List<Passenger> getPassengers() {
        return passengers;
    }
}
