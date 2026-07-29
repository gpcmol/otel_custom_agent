package com.example;

import java.util.List;

public record Garage(List<Customer> customers) {
    public List<Customer> getCustomers() {
        return customers;
    }
}
