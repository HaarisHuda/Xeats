package com.xeats.backend.capacity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.xeats.backend.ordering.OrderPersistenceModels.OrderRecord;
import com.xeats.backend.ordering.OrderPersistenceModels.ReadinessOrder;
import com.xeats.backend.ordering.OrderStatus;

import org.springframework.stereotype.Component;

/** Applies the frozen readiness formula to current orders and immutable slot/policy snapshots. */
@Component
public class OrderReadinessProjection {
    public static final String ONSITE_WAIT_UNAVAILABLE_REASON = "WALK_IN_AND_HANDOVER_TIMES_NOT_MEASURED";

    private static final Comparator<ReadinessOrder> COMMITMENT_ORDER = Comparator
            .comparing((ReadinessOrder row) -> row.order().pickupWindowStartAt())
            .thenComparing(row -> row.order().placedAt())
            .thenComparing(row -> row.order().id());

    /** Results are in counter commitment order and retain terminal positions in each pickup slot. */
    public List<ProjectedOrder> project(List<ReadinessOrder> rows, Instant now) {
        Map<UUID, List<ReadinessOrder>> bySlot = new HashMap<>();
        rows.forEach(row -> bySlot.computeIfAbsent(row.order().pickupSlotId(), ignored -> new ArrayList<>()).add(row));
        Map<UUID, Integer> slotPositions = new HashMap<>();
        bySlot.values().forEach(slotRows -> {
            slotRows.sort(Comparator.comparing((ReadinessOrder row) -> row.order().placedAt())
                    .thenComparing(row -> row.order().id()));
            for (int index = 0; index < slotRows.size(); index++) {
                slotPositions.put(slotRows.get(index).order().id(), index + 1);
            }
        });

        List<ProjectedOrder> projected = new ArrayList<>(rows.size());
        int earlierUnready = 0;
        for (ReadinessOrder row : rows.stream().sorted(COMMITMENT_ORDER).toList()) {
            OrderRecord order = row.order();
            Instant estimate = order.readyAt();
            if (estimate == null && isUnready(order.status())) {
                int position = slotPositions.getOrDefault(order.id(), 1);
                estimate = PickupSlotPlanner.estimate(
                        new PickupSlotPlanner.SlotBounds(order.pickupWindowStartAt(), order.pickupWindowEndAt()),
                        now, position, earlierUnready, row.onlineCapacity(),
                        row.minimumPreparationLeadSeconds(), row.serviceSecondsPerMeal()).estimatedReadyAt();
            }
            projected.add(new ProjectedOrder(row, estimate, estimate != null && order.readyAt() == null
                    && isUnready(order.status()), earlierUnready,
                    slotPositions.getOrDefault(order.id(), 1)));
            if (isUnready(order.status())) {
                earlierUnready++;
            }
        }
        return List.copyOf(projected);
    }

    public Map<UUID, ProjectedOrder> projectByOrderId(List<ReadinessOrder> rows, Instant now) {
        Map<UUID, ProjectedOrder> result = new LinkedHashMap<>();
        for (ProjectedOrder row : project(rows, now)) {
            result.put(row.order().id(), row);
        }
        return Map.copyOf(result);
    }

    /** New orders in a slot sort after every order already committed to that slot. */
    public int unreadyBefore(List<ReadinessOrder> rows, Instant slotStart, boolean includeSameSlot) {
        return (int) rows.stream().filter(row -> isUnready(row.order().status()))
                .filter(row -> includeSameSlot
                        ? !row.order().pickupWindowStartAt().isAfter(slotStart)
                        : row.order().pickupWindowStartAt().isBefore(slotStart))
                .count();
    }

    public static boolean isUnready(OrderStatus status) {
        return status == OrderStatus.ACCEPTED || status == OrderStatus.PREPARING;
    }

    public record ProjectedOrder(
            ReadinessOrder source, Instant estimatedReadyAt, boolean estimateIsApproximate,
            int earlierUnready, int slotPosition) {
        public OrderRecord order() {
            return source.order();
        }
    }
}
