package com.xeats.backend.capacity;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import com.xeats.backend.catalog.management.CatalogManagementDtos.OpeningHours;
import com.xeats.backend.catalog.management.CatalogManagementDtos.OpeningInterval;
import com.xeats.backend.capacity.CapacityDtos.CapacityPolicy;

/** Pure scheduling rules. Input instants make the algorithm deterministic in tests and production. */
final class PickupSlotPlanner {
    private PickupSlotPlanner() { }

    static int onlineCapacity(int totalMeals, int walkInReservePercent) {
        return Math.floorDiv(totalMeals * (100 - walkInReservePercent), 100);
    }

    static List<SlotBounds> generate(
            OpeningHours hours, CapacityPolicy policy, Instant earliest, Instant latestInclusive) {
        if (latestInclusive.isBefore(earliest) || hours.intervals().isEmpty()) {
            return List.of();
        }
        ZoneId zone = ZoneId.of(hours.timeZone());
        LocalDate firstDay = earliest.atZone(zone).toLocalDate().minusDays(1);
        LocalDate lastDay = latestInclusive.atZone(zone).toLocalDate();
        List<SlotBounds> slots = new ArrayList<>();
        for (LocalDate openingDay = firstDay; !openingDay.isAfter(lastDay); openingDay = openingDay.plusDays(1)) {
            DayOfWeek weekday = openingDay.getDayOfWeek();
            for (OpeningInterval interval : hours.intervals()) {
                if (interval.day() != weekday) {
                    continue;
                }
                LocalDate closingDay = interval.closesAt().isAfter(interval.opensAt())
                        ? openingDay : openingDay.plusDays(1);
                Instant opening = atVenueTime(openingDay.atTime(interval.opensAt()), zone);
                Instant closing = atVenueTime(closingDay.atTime(interval.closesAt()), zone);
                Duration step = Duration.ofMinutes(policy.slotDurationMinutes());
                for (Instant start = opening; !start.plus(step).isAfter(closing); start = start.plus(step)) {
                    if (!start.isBefore(earliest) && !start.isAfter(latestInclusive)) {
                        slots.add(new SlotBounds(start, start.plus(step)));
                    }
                }
            }
        }
        return slots.stream().sorted((left, right) -> left.startAt().compareTo(right.startAt())).toList();
    }

    static Estimate estimate(
            SlotBounds slot, CapacityPolicy policy, Instant now, int allocated, int earlierUnready) {
        return estimate(slot, now, allocated + 1, earlierUnready, policy.onlineCapacityPerSlot(),
                policy.minimumPreparationLeadSeconds(), policy.serviceSecondsPerMeal());
    }

    static Estimate estimate(
            SlotBounds slot, Instant now, int position, int earlierUnready,
            int onlineCapacity, int minimumPreparationLeadSeconds, int serviceSecondsPerMeal) {
        long durationSeconds = Duration.between(slot.startAt(), slot.endAt()).toSeconds();
        Instant planned = slot.startAt().plusSeconds(
                Math.floorDiv((2L * position - 1) * durationSeconds, 2L * Math.max(onlineCapacity, 1)));
        Instant backlogEstimate = now.plusSeconds(minimumPreparationLeadSeconds
                + (long) earlierUnready * serviceSecondsPerMeal);
        return new Estimate(planned.isAfter(backlogEstimate) ? planned : backlogEstimate, position);
    }

    static boolean selectable(
            SlotBounds slot, CapacityPolicy policy, Instant now, int allocated, int earlierUnready,
            Instant horizonInclusive) {
        int onlineCapacity = policy.onlineCapacityPerSlot();
        if (onlineCapacity == 0 || allocated >= onlineCapacity
                || slot.startAt().isBefore(now.plusSeconds(policy.minimumPreparationLeadSeconds()))
                || slot.startAt().isAfter(horizonInclusive)) {
            return false;
        }
        return estimate(slot, policy, now, allocated, earlierUnready).estimatedReadyAt().isBefore(slot.endAt());
    }

    static Instant horizon(Instant now, CapacityPolicy policy) {
        return now.plus(Duration.ofMinutes(policy.maximumAdvanceMinutes()));
    }

    private static Instant atVenueTime(LocalDateTime localTime, ZoneId zone) {
        ZonedDateTime zoned = localTime.atZone(zone);
        return zoned.toInstant();
    }

    record SlotBounds(Instant startAt, Instant endAt) { }

    record Estimate(Instant estimatedReadyAt, int position) { }
}
