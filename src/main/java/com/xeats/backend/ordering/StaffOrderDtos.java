package com.xeats.backend.ordering;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.xeats.backend.capacity.CapacityDtos.PickupWindow;
import com.xeats.backend.catalog.discovery.DiscoveryDtos.MenuItem;
import com.xeats.backend.catalog.discovery.DiscoveryDtos.Money;

/** Counter-staff workspace and queue projections. Pickup codes are intentionally absent. */
public final class StaffOrderDtos {
    private StaffOrderDtos() { }

    public record StaffOrder(
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
            String employeeDisplayLabel) { }

    public record StaffOrderPage(
            List<StaffOrder> items,
            int page,
            int size,
            int totalItems,
            int totalPages) {
        public StaffOrderPage {
            items = List.copyOf(items);
        }
    }

    public record QueueCounts(int accepted, int preparing, int ready, int due, int overdue) { }

    public record StaffCounter(
            UUID id,
            UUID vendorId,
            String name,
            String pickupInstructions,
            boolean active,
            boolean acceptingOnlineOrders,
            MenuItem item,
            boolean openNow,
            QueueCounts queueCounts,
            PickupWindow nextPickupWindow) { }

    public record StaffCounters(List<StaffCounter> items) {
        public StaffCounters {
            items = List.copyOf(items);
        }
    }
}
