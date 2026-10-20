package com.xeats.backend.ordering;

public enum OrderStatus {
    ACCEPTED,
    PREPARING,
    READY,
    COLLECTED,
    EXPIRED;

    public boolean isActive() {
        return this == ACCEPTED || this == PREPARING || this == READY;
    }

    public boolean isTerminal() {
        return !isActive();
    }
}
