package com.example;

import java.util.List;

public record Garage(List<Customer> customers, List<Car> parkedCars) {
    public List<Customer> getCustomers() {
        return customers;
    }

    public Result park(Car car) {
        final boolean accepted = parkedCars.add(car);
        return new Result(accepted, accepted ? "accepted" : "rejected");
    }
}
