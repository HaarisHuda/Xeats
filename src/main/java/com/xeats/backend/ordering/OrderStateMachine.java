package com.xeats.backend.ordering;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** The single source of truth for permitted order status transitions. */
public final class OrderStateMachine {
    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = Map.of(
            OrderStatus.ACCEPTED, EnumSet.of(OrderStatus.PREPARING, OrderStatus.EXPIRED),
            OrderStatus.PREPARING, EnumSet.of(OrderStatus.READY, OrderStatus.EXPIRED),
            OrderStatus.READY, EnumSet.of(OrderStatus.COLLECTED, OrderStatus.EXPIRED),
            OrderStatus.COLLECTED, EnumSet.noneOf(OrderStatus.class),
            OrderStatus.EXPIRED, EnumSet.noneOf(OrderStatus.class));

    private OrderStateMachine() {
    }

    public static boolean canTransition(OrderStatus current, OrderStatus requested) {
        return TRANSITIONS.get(current).contains(requested);
    }

    public static void requireTransition(OrderStatus current, OrderStatus requested) {
        if (!canTransition(current, requested)) {
            throw new InvalidOrderTransitionException(current, requested);
        }
    }
}
