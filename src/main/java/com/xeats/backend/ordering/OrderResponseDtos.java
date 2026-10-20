package com.xeats.backend.ordering;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.xeats.backend.catalog.discovery.DiscoveryDtos.Money;
import com.xeats.backend.capacity.CapacityDtos.PickupWindow;

public final class OrderResponseDtos {
    private OrderResponseDtos() {
    }

    public record EmployeeOrder(
            UUID id,
            UUID counterId,
            String counterName,
            String pickupInstructions,
            String itemName,
            Money itemPrice,
            PickupWindow pickupWindow,
            Instant estimatedReadyAt,
            boolean estimateIsApproximate,
            Integer expectedOnsiteWaitMinutes,
            String onsiteWaitUnavailableReason,
            OrderStatus status,
            long version,
            Instant placedAt,
            Instant acceptedAt,
            Instant preparingAt,
            Instant readyAt,
            Instant collectedAt,
            Instant expiredAt,
            String expiryReason,
            boolean overdue,
            String simulatedPaymentStatus,
            PickupTokenView pickupToken) {
    }

    public record PickupTokenView(String code, boolean redeemable) {
    }

    /** Employee list representation deliberately excludes the secret pickup code. */
    public record EmployeeOrderSummary(
            UUID id,
            UUID counterId,
            String counterName,
            String pickupInstructions,
            String itemName,
            Money itemPrice,
            PickupWindow pickupWindow,
            Instant estimatedReadyAt,
            boolean estimateIsApproximate,
            Integer expectedOnsiteWaitMinutes,
            String onsiteWaitUnavailableReason,
            OrderStatus status,
            long version,
            Instant placedAt,
            Instant acceptedAt,
            Instant preparingAt,
            Instant readyAt,
            Instant collectedAt,
            Instant expiredAt,
            String expiryReason,
            boolean overdue,
            String simulatedPaymentStatus) {
    }

    public record EmployeeOrderPage(
            List<EmployeeOrderSummary> items,
            int page,
            int size,
            int totalItems,
            int totalPages) {
        public EmployeeOrderPage {
            items = List.copyOf(items);
        }
    }

    public record ConfirmationOutcome(UUID orderId, boolean replayed) {
    }

    public record ConfirmationResponse(EmployeeOrder order, boolean replayed) {
    }
}
