package com.xeats.backend.ordering;

import java.time.Instant;
import java.util.UUID;

public final class OrderPersistenceModels {
    private OrderPersistenceModels() {
    }

    public record OrderRecord(
            UUID id,
            UUID employeeId,
            UUID counterId,
            UUID menuItemId,
            UUID pickupSlotId,
            String itemName,
            long itemPriceMinor,
            String itemCurrency,
            String counterName,
            String pickupInstructions,
            Instant pickupWindowStartAt,
            Instant pickupWindowEndAt,
            OrderStatus status,
            long version,
            String simulatedPaymentStatus,
            Instant placedAt,
            Instant acceptedAt,
            Instant preparingAt,
            Instant readyAt,
            Instant collectedAt,
            Instant expiredAt,
            String expiredReason) {
    }

    public record OrderWithToken(OrderRecord order, PickupToken token) {
    }

    /** Order plus immutable slot/policy data used for privacy-safe readiness projections. */
    public record ReadinessOrder(
            OrderRecord order,
            int onlineCapacity,
            int minimumPreparationLeadSeconds,
            int serviceSecondsPerMeal) { }

    public record ActiveOrderClaim(UUID employeeId, UUID counterId, UUID orderId, Instant claimedAt) {
    }

    public record PickupToken(UUID orderId, String code, Instant issuedAt, Instant redeemedAt, Instant invalidatedAt) {
        public boolean isRedeemable() {
            return redeemedAt == null && invalidatedAt == null;
        }
    }

    public record OrderStatusEvent(
            UUID orderId,
            long version,
            OrderStatus previousStatus,
            OrderStatus newStatus,
            UUID actorUserId,
            Instant occurredAt,
            String expiredReason) {
    }

    public record IdempotencyRecord(
            UUID employeeId,
            OrderIdempotencyOperation operation,
            UUID key,
            String requestFingerprintSha256,
            UUID orderId,
            int firstHttpStatus,
            Instant createdAt) {
    }
}
