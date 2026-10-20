package com.xeats.backend.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OrderStateMachineTest {
    @Test
    void permitsOnlyDocumentedProgressionAndManualExpiryFromActiveStates() {
        assertThat(OrderStateMachine.canTransition(OrderStatus.ACCEPTED, OrderStatus.PREPARING)).isTrue();
        assertThat(OrderStateMachine.canTransition(OrderStatus.PREPARING, OrderStatus.READY)).isTrue();
        assertThat(OrderStateMachine.canTransition(OrderStatus.READY, OrderStatus.COLLECTED)).isTrue();
        assertThat(OrderStateMachine.canTransition(OrderStatus.ACCEPTED, OrderStatus.EXPIRED)).isTrue();
        assertThat(OrderStateMachine.canTransition(OrderStatus.PREPARING, OrderStatus.EXPIRED)).isTrue();
        assertThat(OrderStateMachine.canTransition(OrderStatus.READY, OrderStatus.EXPIRED)).isTrue();
        assertThat(OrderStatus.ACCEPTED.isActive()).isTrue();
        assertThat(OrderStatus.READY.isActive()).isTrue();
        assertThat(OrderStatus.COLLECTED.isTerminal()).isTrue();
        assertThat(OrderStatus.EXPIRED.isTerminal()).isTrue();
    }

    @Test
    void rejectsSkippedRepeatedAndPostTerminalTransitions() {
        assertThatThrownBy(() -> OrderStateMachine.requireTransition(OrderStatus.ACCEPTED, OrderStatus.READY))
                .isInstanceOf(InvalidOrderTransitionException.class);
        assertThatThrownBy(() -> OrderStateMachine.requireTransition(OrderStatus.READY, OrderStatus.PREPARING))
                .isInstanceOf(InvalidOrderTransitionException.class);
        assertThatThrownBy(() -> OrderStateMachine.requireTransition(OrderStatus.COLLECTED, OrderStatus.EXPIRED))
                .isInstanceOf(InvalidOrderTransitionException.class);
    }
}
