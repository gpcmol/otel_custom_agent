package com.example;

import java.util.List;

public record Garage(List<Customer> customers, List<Car> parkedCars) {
    public List<Customer> getCustomers() {
        return customers;
    }

    public void park(Car car) {
        parkedCars.add(car);
    }
}