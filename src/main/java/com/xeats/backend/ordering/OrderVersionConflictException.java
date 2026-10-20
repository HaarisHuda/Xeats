package com.xeats.backend.ordering;

public final class OrderVersionConflictException extends RuntimeException {
    public OrderVersionConflictException(long expected, long actual) {
        super("Order version %d is stale; current version is %d.".formatted(expected, actual));
    }
}
