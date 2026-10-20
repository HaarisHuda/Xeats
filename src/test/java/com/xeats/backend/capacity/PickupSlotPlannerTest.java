package com.xeats.backend.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import com.xeats.backend.catalog.management.CatalogManagementDtos.OpeningHours;
import com.xeats.backend.catalog.management.CatalogManagementDtos.OpeningInterval;
import com.xeats.backend.capacity.CapacityDtos.CapacityPolicy;
import com.xeats.backend.capacity.PickupSlotPlanner.SlotBounds;

import org.junit.jupiter.api.Test;

class PickupSlotPlannerTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-19T05:55:00Z"), ZoneOffset.UTC);
    private static final Instant NOW = CLOCK.instant();

    @Test
    void floorsOnlineCapacityAndAssignsRoundingRemainderToWalkIns() {
        assertThat(PickupSlotPlanner.onlineCapacity(6, 30)).isEqualTo(4);
        assertThat(PickupSlotPlanner.onlineCapacity(5, 30)).isEqualTo(3);
        assertThat(PickupSlotPlanner.onlineCapacity(6, 100)).isZero();
    }

    @Test
    void generatesOnlyWholeWindowsInsideLocalHoursAndRequestedHorizon() {
        OpeningHours hours = new OpeningHours("Asia/Kolkata", List.of(
                new OpeningInterval(DayOfWeek.MONDAY, LocalTime.of(11, 0), LocalTime.NOON)));
        List<SlotBounds> slots = PickupSlotPlanner.generate(
                hours, policy(3, 120, 60), NOW, NOW.plus(Duration.ofMinutes(10)));

        assertThat(slots).hasSize(3);
        assertThat(slots.getFirst().startAt()).isEqualTo(Instant.parse("2026-10-19T05:55:00Z"));
        assertThat(slots.getLast().startAt()).isEqualTo(Instant.parse("2026-10-19T06:05:00Z"));
        assertThat(slots.getLast().endAt()).isEqualTo(Instant.parse("2026-10-19T06:10:00Z"));
        assertThat(slots).allSatisfy(slot -> assertThat(slot.endAt())
                .isBeforeOrEqualTo(Instant.parse("2026-10-19T06:30:00Z")));
    }

    @Test
    void closingBoundaryIsExclusiveAndDoesNotCreatePartialWindows() {
        OpeningHours hours = new OpeningHours("Asia/Kolkata", List.of(
                new OpeningInterval(DayOfWeek.MONDAY, LocalTime.of(11, 0), LocalTime.of(11, 12))));
        List<SlotBounds> slots = PickupSlotPlanner.generate(
                hours, policy(3, 0, 60), Instant.parse("2026-10-19T05:30:00Z"),
                Instant.parse("2026-10-19T06:00:00Z"));

        assertThat(slots).hasSize(2);
        assertThat(slots.getLast().endAt()).isEqualTo(Instant.parse("2026-10-19T05:40:00Z"));
        assertThat(slots).allSatisfy(slot -> assertThat(slot.endAt())
                .isBeforeOrEqualTo(Instant.parse("2026-10-19T05:42:00Z")));
    }

    @Test
    void estimatedReadyTimeUsesTheLaterOfPlannedPositionAndBacklog() {
        CapacityPolicy policy = new CapacityPolicy(1, 5, 6, 30, 4, 120, 60, 75, NOW);
        SlotBounds firstPosition = new SlotBounds(
                Instant.parse("2026-10-19T06:00:00Z"), Instant.parse("2026-10-19T06:05:00Z"));
        SlotBounds nextPosition = new SlotBounds(
                Instant.parse("2026-10-19T06:00:00Z"), Instant.parse("2026-10-19T06:05:00Z"));

        assertThat(PickupSlotPlanner.estimate(firstPosition, policy, NOW, 0, 0).estimatedReadyAt())
                .isEqualTo(Instant.parse("2026-10-19T06:00:37Z"));
        assertThat(PickupSlotPlanner.estimate(nextPosition, policy, NOW, 1, 3).estimatedReadyAt())
                .isEqualTo(Instant.parse("2026-10-19T06:01:52Z"));
        assertThat(PickupSlotPlanner.estimate(nextPosition, policy, NOW, 1, 4).estimatedReadyAt())
                .isEqualTo(Instant.parse("2026-10-19T06:02:00Z"));
    }

    @Test
    void enforcesLeadHorizonCapacityAndStrictClosingForSelection() {
        CapacityPolicy normal = policy(3, 120, 60);
        SlotBounds tooSoon = new SlotBounds(NOW, NOW.plus(Duration.ofMinutes(5)));
        SlotBounds atHorizon = new SlotBounds(
                NOW.plus(Duration.ofMinutes(10)), NOW.plus(Duration.ofMinutes(15)));
        SlotBounds beyondHorizon = new SlotBounds(
                NOW.plus(Duration.ofMinutes(10).plusSeconds(1)), NOW.plus(Duration.ofMinutes(15).plusSeconds(1)));
        SlotBounds tooLateToPrepare = new SlotBounds(NOW, NOW.plusSeconds(120));

        assertThat(PickupSlotPlanner.selectable(tooSoon, normal, NOW, 0, 0, NOW.plus(Duration.ofMinutes(10)))
                ).isFalse();
        assertThat(PickupSlotPlanner.selectable(atHorizon, normal, NOW, 0, 0, NOW.plus(Duration.ofMinutes(10)))
                ).isTrue();
        assertThat(PickupSlotPlanner.selectable(beyondHorizon, normal, NOW, 0, 0,
                NOW.plus(Duration.ofMinutes(10)))).isFalse();
        assertThat(PickupSlotPlanner.selectable(tooLateToPrepare, normal, NOW, 0, 10,
                NOW.plus(Duration.ofMinutes(10)))).isFalse();
        assertThat(PickupSlotPlanner.selectable(atHorizon, normal, NOW, 3, 0,
                NOW.plus(Duration.ofMinutes(10)))).isFalse();
        assertThat(PickupSlotPlanner.selectable(atHorizon, policy(0, 0, 60), NOW, 0, 0,
                NOW.plus(Duration.ofMinutes(10)))).isFalse();
    }

    private static CapacityPolicy policy(int onlineCapacity, int leadSeconds, int horizonMinutes) {
        int total = onlineCapacity == 0 ? 6 : 5;
        int walkInPercent = onlineCapacity == 0 ? 100 : 30;
        return new CapacityPolicy(1, 5, total, walkInPercent, onlineCapacity,
                leadSeconds, horizonMinutes, 75, NOW);
    }
}
