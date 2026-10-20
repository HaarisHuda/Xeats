package com.xeats.backend.ordering;

import java.util.UUID;

public final class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException(UUID orderId) {
        super("Order %s was not found.".formatted(orderId));
    }
}
