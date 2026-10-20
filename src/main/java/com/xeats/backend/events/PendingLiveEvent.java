package com.xeats.backend.events;

import java.time.Instant;
import java.util.UUID;

/** Internal routing metadata. Employee identity is deliberately excluded from the wire envelope. */
record PendingLiveEvent(
        UUID id,
        LiveEventType type,
        UUID entityId,
        UUID counterId,
        UUID employeeId,
        long entityVersion,
        Instant occurredAt) {

    LiveEvent envelope() {
        return new LiveEvent(entityId, counterId, entityVersion, occurredAt);
    }
}
