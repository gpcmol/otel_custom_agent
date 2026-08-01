package com.example;

import java.util.List;

public record Car(
    String brand,
    String model,
    int year,
    String color,
    String licensePlate,
    String vin,
    long mileage,
    String fuelType,
    String transmission,
    List<Passenger> passengers) {

    public String getBrand() {
        return brand;
    }

    public String getModel() {
        return model;
    }

    public int getYear() {
        return year;
    }

    public String getColor() {
        return color;
    }

    public String getLicensePlate() {
        return licensePlate;
    }

    public String getVin() {
        return vin;
    }

    public long getMileage() {
        return mileage;
    }

    public String getFuelType() {
        return fuelType;
    }

    public String getTransmission() {
        return transmission;
    }

    public List<Passenger> getPassengers() {
        return passengers;
    }
}