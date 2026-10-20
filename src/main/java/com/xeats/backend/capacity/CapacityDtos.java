package com.xeats.backend.capacity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.xeats.backend.catalog.discovery.DiscoveryDtos.CounterSummary;

/** Public employee and counter-staff capacity responses. */
public final class CapacityDtos {
    private CapacityDtos() { }

    public record CapacityPolicy(
            int revision,
            int slotDurationMinutes,
            int totalMealsPerSlot,
            int walkInReservePercent,
            int onlineCapacityPerSlot,
            int minimumPreparationLeadSeconds,
            int maximumAdvanceMinutes,
            int serviceSecondsPerMeal,
            Instant effectiveAt) { }

    public record PickupWindow(UUID id, Instant startAt, Instant endAt) { }

    public record SelectableWindow(
            UUID id,
            Instant startAt,
            Instant endAt,
            int onlineCapacity,
            int allocated,
            int remaining,
            Instant estimatedReadyAt,
            boolean estimateIsApproximate,
            Integer expectedOnsiteWaitMinutes,
            String onsiteWaitUnavailableReason) { }

    public record UnavailableWindow(UUID id, Instant startAt, Instant endAt, String reason) { }

    public record OrderOptions(
            CounterSummary counter,
            String timeZone,
            List<SelectableWindow> selectableWindows,
            List<UnavailableWindow> unavailableWindows,
            UUID recommendedWindowId,
            String noOptionsReason) {
        public OrderOptions {
            selectableWindows = List.copyOf(selectableWindows);
            unavailableWindows = List.copyOf(unavailableWindows);
        }
    }

    public record StaffCapacityWindow(
            UUID id,
            Instant startAt,
            Instant endAt,
            int totalMeals,
            int walkInReserved,
            int onlineCapacity,
            int confirmedAllocations,
            int remainingOnlineAllocations,
            int unreadyEarlierOrders,
            Instant estimatedLatestReadyAt,
            boolean estimateIsApproximate,
            Integer expectedOnsiteWaitMinutes,
            String onsiteWaitUnavailableReason,
            boolean selectable) { }

    public record CounterCapacityStatus(
            UUID counterId, String timeZone, CapacityPolicy policy, List<StaffCapacityWindow> windows) {
        public CounterCapacityStatus {
            windows = List.copyOf(windows);
        }
    }
}
