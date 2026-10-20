package com.xeats.backend.ordering;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.xeats.backend.catalog.discovery.DiscoveryDtos.Money;
import com.xeats.backend.capacity.CapacityDtos.PickupWindow;
import com.xeats.backend.capacity.OrderReadinessProjection;
import com.xeats.backend.capacity.OrderReadinessProjection.ProjectedOrder;
import com.xeats.backend.common.web.ApiErrorCode;
import com.xeats.backend.common.web.ApiProblemException;
import com.xeats.backend.ordering.OrderPersistenceModels.OrderRecord;
import com.xeats.backend.ordering.OrderPersistenceModels.OrderWithToken;
import com.xeats.backend.ordering.OrderPersistenceModels.ReadinessOrder;
import com.xeats.backend.ordering.OrderResponseDtos.EmployeeOrder;
import com.xeats.backend.ordering.OrderResponseDtos.EmployeeOrderPage;
import com.xeats.backend.ordering.OrderResponseDtos.EmployeeOrderSummary;
import com.xeats.backend.ordering.OrderResponseDtos.PickupTokenView;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class EmployeeOrderReadService {
    private static final List<OrderStatus> ACTIVE_STATUSES = List.of(
            OrderStatus.ACCEPTED, OrderStatus.PREPARING, OrderStatus.READY);

    private final OrderPersistenceRepository orders;
    private final OrderReadinessProjection readiness;
    private final Clock clock;

    public EmployeeOrderReadService(
            OrderPersistenceRepository orders, OrderReadinessProjection readiness, Clock clock) {
        this.orders = orders;
        this.readiness = readiness;
        this.clock = clock;
    }

    public EmployeeOrderPage list(UUID employeeId, List<OrderStatus> requestedStatuses, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_ERROR,
                    "Page must be non-negative and size must be between 1 and 100.");
        }
        List<OrderStatus> statuses = requestedStatuses == null || requestedStatuses.isEmpty()
                ? ACTIVE_STATUSES
                : requestedStatuses.stream().distinct().toList();
        long total = orders.countOrdersForEmployee(employeeId, statuses);
        long offset = (long) page * size;
        List<OrderRecord> records = orders.findOrdersForEmployee(employeeId, statuses, size, offset);
        Set<UUID> counterIds = records.stream().map(OrderRecord::counterId).collect(java.util.stream.Collectors.toSet());
        List<ReadinessOrder> workload = orders.findReadinessOrdersForCounters(counterIds);
        Map<UUID, ProjectedOrder> projected = readiness.projectByOrderId(workload, clock.instant());
        List<EmployeeOrderSummary> items = records.stream()
                .map(order -> summary(order, projected.get(order.id()))).toList();
        int totalItems = Math.toIntExact(total);
        int totalPages = totalItems == 0 ? 0 : (int) ((totalItems + (long) size - 1) / size);
        return new EmployeeOrderPage(items, page, size, totalItems, totalPages);
    }

    public EmployeeOrder get(UUID employeeId, UUID orderId) {
        OrderWithToken order = orders.findOwnedByIdWithToken(orderId, employeeId)
                .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, ApiErrorCode.NOT_FOUND,
                        "The requested resource was not found."));
        Map<UUID, ProjectedOrder> projected = readiness.projectByOrderId(
                orders.findReadinessOrdersForCounter(order.order().counterId()), clock.instant());
        return detail(order, projected.get(order.order().id()));
    }

    private EmployeeOrderSummary summary(OrderRecord order, ProjectedOrder projected) {
        return new EmployeeOrderSummary(
                order.id(), order.counterId(), order.counterName(), order.pickupInstructions(), order.itemName(),
                new Money(order.itemPriceMinor(), order.itemCurrency()),
                new PickupWindow(order.pickupSlotId(), order.pickupWindowStartAt(), order.pickupWindowEndAt()),
                projected == null ? null : projected.estimatedReadyAt(),
                projected != null && projected.estimateIsApproximate(), null,
                OrderReadinessProjection.ONSITE_WAIT_UNAVAILABLE_REASON,
                order.status(), order.version(), order.placedAt(), order.acceptedAt(),
                order.preparingAt(), order.readyAt(), order.collectedAt(), order.expiredAt(), order.expiredReason(),
                overdue(order), order.simulatedPaymentStatus());
    }

    private EmployeeOrder detail(OrderWithToken orderWithToken, ProjectedOrder projected) {
        OrderRecord order = orderWithToken.order();
        return new EmployeeOrder(
                order.id(), order.counterId(), order.counterName(), order.pickupInstructions(), order.itemName(),
                new Money(order.itemPriceMinor(), order.itemCurrency()),
                new PickupWindow(order.pickupSlotId(), order.pickupWindowStartAt(), order.pickupWindowEndAt()),
                projected == null ? null : projected.estimatedReadyAt(),
                projected != null && projected.estimateIsApproximate(), null,
                OrderReadinessProjection.ONSITE_WAIT_UNAVAILABLE_REASON,
                order.status(), order.version(), order.placedAt(), order.acceptedAt(),
                order.preparingAt(), order.readyAt(), order.collectedAt(), order.expiredAt(), order.expiredReason(),
                overdue(order), order.simulatedPaymentStatus(),
                new PickupTokenView(orderWithToken.token().code(), orderWithToken.token().isRedeemable()));
    }

    private boolean overdue(OrderRecord order) {
        return order.status().isActive() && !order.pickupWindowEndAt().isAfter(clock.instant());
    }
}
