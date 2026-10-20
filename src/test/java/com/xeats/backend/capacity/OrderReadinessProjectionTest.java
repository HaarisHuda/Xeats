package com.xeats.backend.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.xeats.backend.ordering.OrderPersistenceModels.OrderRecord;
import com.xeats.backend.ordering.OrderPersistenceModels.ReadinessOrder;
import com.xeats.backend.ordering.OrderStatus;

import org.junit.jupiter.api.Test;

class OrderReadinessProjectionTest {
    private static final Instant NOW = Instant.parse("2026-10-20T05:55:00Z");
    private static final Instant SLOT_START = Instant.parse("2026-10-20T06:00:00Z");
    private static final Instant SLOT_END = SLOT_START.plusSeconds(300);
    private static final UUID SLOT_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");

    private final OrderReadinessProjection projection = new OrderReadinessProjection();

    @Test
    void extraAcceptedAndPreparingWorkShiftsLaterReadinessByConfiguredServiceUnits() {
        List<ReadinessOrder> workload = new ArrayList<>(List.of(
                row(1, OrderStatus.ACCEPTED, SLOT_START.minusSeconds(30), null, 100, 120, 75),
                row(2, OrderStatus.PREPARING, SLOT_START.minusSeconds(20), null, 100, 120, 75),
                row(3, OrderStatus.ACCEPTED, SLOT_START.minusSeconds(10), null, 100, 120, 75)));

        Instant now = SLOT_START.minusSeconds(30);
        Instant beforeExtraOrder = projection.project(workload, now).getLast().estimatedReadyAt();
        workload.add(row(4, OrderStatus.ACCEPTED, SLOT_START.minusSeconds(5), null, 100, 120, 75));
        Instant afterExtraOrder = projection.project(workload, now).getLast().estimatedReadyAt();

        assertThat(beforeExtraOrder).isEqualTo(now.plusSeconds(120 + 2 * 75L));
        assertThat(afterExtraOrder).isEqualTo(now.plusSeconds(120 + 3 * 75L));
    }

    @Test
    void readyAndTerminalOrdersUseActualReadinessOrNullAndKeepTheirSlotPositions() {
        Instant readyAt = NOW.minusSeconds(25);
        Instant collectedReadyAt = NOW.minusSeconds(80);
        List<ReadinessOrder> workload = List.of(
                row(1, OrderStatus.EXPIRED, SLOT_START.minusSeconds(40), null, 100, 120, 75),
                row(2, OrderStatus.READY, SLOT_START.minusSeconds(30), readyAt, 100, 120, 75),
                row(3, OrderStatus.COLLECTED, SLOT_START.minusSeconds(20), collectedReadyAt, 100, 120, 75),
                row(4, OrderStatus.ACCEPTED, SLOT_START.minusSeconds(10), null, 100, 120, 75));

        List<OrderReadinessProjection.ProjectedOrder> projected = projection.project(workload, NOW);

        assertThat(projected).extracting(OrderReadinessProjection.ProjectedOrder::slotPosition)
                .containsExactly(1, 2, 3, 4);
        assertThat(projected.get(0).estimatedReadyAt()).isNull();
        assertThat(projected.get(0).estimateIsApproximate()).isFalse();
        assertThat(projected.get(1).estimatedReadyAt()).isEqualTo(readyAt);
        assertThat(projected.get(1).estimateIsApproximate()).isFalse();
        assertThat(projected.get(2).estimatedReadyAt()).isEqualTo(collectedReadyAt);
        assertThat(projected.get(3).earlierUnready()).isZero();
        assertThat(projected.get(3).estimatedReadyAt()).isAfter(SLOT_START);
        assertThat(projected.get(3).estimateIsApproximate()).isTrue();
        assertThat(projection.unreadyBefore(workload, SLOT_START, true)).isEqualTo(1);
    }

    @Test
    void eachBookedSlotUsesItsOwnPreparationAndServicePolicySnapshot() {
        ReadinessOrder earlier = row(1, OrderStatus.PREPARING, SLOT_START, null, 100, 60, 30);
        ReadinessOrder later = row(2, OrderStatus.ACCEPTED, SLOT_START.plusSeconds(300), null,
                100, 180, 90);

        Instant now = SLOT_START.plusSeconds(270);
        OrderReadinessProjection.ProjectedOrder projected = projection.project(List.of(earlier, later), now)
                .getLast();

        assertThat(projected.estimatedReadyAt()).isEqualTo(now.plusSeconds(180 + 90L));
    }

    private static ReadinessOrder row(
            int id, OrderStatus status, Instant placedAt, Instant readyAt,
            int onlineCapacity, int leadSeconds, int serviceSeconds) {
        UUID orderId = new UUID(0, id);
        Instant preparingAt = status == OrderStatus.PREPARING || status == OrderStatus.READY
                || status == OrderStatus.COLLECTED ? placedAt : null;
        Instant collectedAt = status == OrderStatus.COLLECTED ? placedAt.plusSeconds(5) : null;
        Instant expiredAt = status == OrderStatus.EXPIRED ? placedAt.plusSeconds(5) : null;
        OrderRecord order = new OrderRecord(orderId, new UUID(0, 100 + id), new UUID(0, 200), new UUID(0, 300),
                SLOT_ID, "Meal", 1000, "INR", "Counter", "At the counter", SLOT_START, SLOT_END,
                status, 1, "SIMULATED_SUCCEEDED", placedAt, placedAt, preparingAt, readyAt,
                collectedAt, expiredAt, status == OrderStatus.EXPIRED ? "Expired in test" : null);
        return new ReadinessOrder(order, onlineCapacity, leadSeconds, serviceSeconds);
    }
}
