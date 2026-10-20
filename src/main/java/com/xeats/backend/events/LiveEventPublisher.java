package com.xeats.backend.events;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Registers live notifications for delivery only after their owning database transaction commits. */
@Component
public class LiveEventPublisher {
    private final AuthorizedLiveEventHub hub;

    public LiveEventPublisher(AuthorizedLiveEventHub hub) {
        this.hub = hub;
    }

    public void orderStatusChanged(UUID orderId, UUID employeeId, UUID counterId, long version, Instant occurredAt) {
        publish(LiveEventType.ORDER_STATUS_CHANGED, orderId, counterId, employeeId, version, occurredAt);
    }

    public void counterChanged(
            LiveEventType type, UUID entityId, UUID counterId, long version, Instant occurredAt) {
        if (type == LiveEventType.ORDER_STATUS_CHANGED) {
            throw new IllegalArgumentException("Order status changes require an employee audience.");
        }
        publish(type, entityId, counterId, null, version, occurredAt);
    }

    private void publish(
            LiveEventType type, UUID entityId, UUID counterId, UUID employeeId, long version, Instant occurredAt) {
        if (version < 1) {
            throw new IllegalArgumentException("A live event version must be positive.");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Live notifications must be registered inside a transaction.");
        }
        PendingLiveEvent event = new PendingLiveEvent(
                UUID.randomUUID(), type, entityId, counterId, employeeId, version, occurredAt);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                hub.publish(event);
            }
        });
    }
}
