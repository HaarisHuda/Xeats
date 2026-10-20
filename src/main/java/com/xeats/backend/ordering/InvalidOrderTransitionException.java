package com.xeats.backend.ordering;

public final class InvalidOrderTransitionException extends RuntimeException {
    public InvalidOrderTransitionException(OrderStatus current, OrderStatus requested) {
        super("Order cannot transition from %s to %s.".formatted(current, requested));
    }
}
