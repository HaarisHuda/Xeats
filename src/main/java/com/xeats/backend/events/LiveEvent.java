package com.xeats.backend.events;

import java.time.Instant;
import java.util.UUID;

/** Privacy-safe event envelope; clients refetch the authorized resource snapshot. */
public record LiveEvent(UUID entityId, UUID counterId, long entityVersion, Instant occurredAt) { }
