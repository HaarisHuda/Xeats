package com.xeats.backend.ordering;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.xeats.backend.capacity.CapacityDtos.CounterCapacityStatus;
import com.xeats.backend.capacity.CapacityDtos.PickupWindow;
import com.xeats.backend.capacity.CapacityRepository;
import com.xeats.backend.capacity.CapacityRepository.CounterData;
import com.xeats.backend.capacity.OrderReadinessProjection;
import com.xeats.backend.catalog.discovery.DiscoveryDtos.CounterSummary;
import com.xeats.backend.catalog.management.CatalogManagementDtos.OpeningHours;
import com.xeats.backend.catalog.management.CatalogManagementDtos.OpeningInterval;
import com.xeats.backend.common.web.ApiErrorCode;
import com.xeats.backend.common.web.ApiProblemException;
import com.xeats.backend.identity.IdentityAuthorizationService;
import com.xeats.backend.identity.XeatsPrincipal;
import com.xeats.backend.ordering.OrderPersistenceModels.OrderRecord;
import com.xeats.backend.ordering.OrderPersistenceModels.ReadinessOrder;
import com.xeats.backend.ordering.StaffOrderDtos.QueueCounts;
import com.xeats.backend.ordering.StaffOrderDtos.StaffCounter;
import com.xeats.backend.ordering.StaffOrderDtos.StaffCounters;
import com.xeats.backend.ordering.StaffOrderDtos.StaffOrder;
import com.xeats.backend.ordering.StaffOrderDtos.StaffOrderPage;
import com.xeats.backend.ordering.StaffOrderRepository.StaffQueueOrder;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class StaffOrderService {
    private static final List<OrderStatus> ACTIVE_STATUSES = List.of(
            OrderStatus.ACCEPTED, OrderStatus.PREPARING, OrderStatus.READY);
    private static final Pattern STRONG_VERSION = Pattern.compile("^\"([1-9][0-9]*)\"$");
    private static final Comparator<QueueEntry> COMMITMENT_ORDER = Comparator
            .comparing((QueueEntry entry) -> entry.order().pickupWindowStartAt())
            .thenComparing(QueueEntry::estimatedReadyAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(entry -> entry.order().placedAt())
            .thenComparing(entry -> entry.order().id());

    private final StaffOrderRepository staffOrders;
    private final OrderPersistenceRepository orders;
    private final OrderLifecycleService lifecycle;
    private final CapacityRepository capacity;
    private final IdentityAuthorizationService authorization;
    private final PickupRedemptionAttemptService redemptionAttempts;
    private final OrderReadinessProjection readiness;
    private final Clock clock;

    public StaffOrderService(
            StaffOrderRepository staffOrders,
            OrderPersistenceRepository orders,
            OrderLifecycleService lifecycle,
            CapacityRepository capacity,
            IdentityAuthorizationService authorization,
            PickupRedemptionAttemptService redemptionAttempts,
            OrderReadinessProjection readiness,
            Clock clock) {
        this.staffOrders = staffOrders;
        this.orders = orders;
        this.lifecycle = lifecycle;
        this.capacity = capacity;
        this.authorization = authorization;
        this.redemptionAttempts = redemptionAttempts;
        this.readiness = readiness;
        this.clock = clock;
    }

    public StaffCounters listAssignedCounters(XeatsPrincipal actor) {
        boolean includeAll = authorization.isDemoAdmin(actor.userId());
        Instant now = clock.instant();
        List<StaffCounter> items = staffOrders.findCounters(actor.userId(), includeAll).stream()
                .map(counter -> staffCounter(counter, now)).toList();
        return new StaffCounters(items);
    }

    public StaffOrderPage listCounterOrders(
            UUID counterId,
            List<OrderStatus> requestedStatuses,
            Instant pickupWindowStart,
            Boolean overdueOnly,
            int page,
            int size,
            XeatsPrincipal actor) {
        if (page < 0 || size < 1 || size > 100) {
            throw invalid("Page must be non-negative and size must be between 1 and 100.");
        }
        requireExistingCounterAccess(counterId, actor);
        List<OrderStatus> statuses = requestedStatuses == null || requestedStatuses.isEmpty()
                ? ACTIVE_STATUSES
                : requestedStatuses.stream().distinct().toList();
        List<QueueEntry> filtered = project(staffOrders.findOrdersForCounter(counterId), clock.instant()).stream()
                .filter(entry -> statuses.contains(entry.order().status()))
                .filter(entry -> pickupWindowStart == null
                        || pickupWindowStart.equals(entry.order().pickupWindowStartAt()))
                .filter(entry -> overdueOnly == null || overdueOnly == entry.overdue())
                .sorted(COMMITMENT_ORDER)
                .toList();
        long offset = (long) page * size;
        int start = (int) Math.min(offset, filtered.size());
        int end = Math.min(start + size, filtered.size());
        int totalItems = filtered.size();
        int totalPages = totalItems == 0 ? 0 : (totalItems + size - 1) / size;
        return new StaffOrderPage(filtered.subList(start, end).stream().map(this::toStaffOrder).toList(),
                page, size, totalItems, totalPages);
    }

    public StaffOrder transition(
            UUID orderId, OrderStatus nextStatus, String ifMatch, String expiryReason, XeatsPrincipal actor) {
        OrderPersistenceRepository.OrderIdentity identity = orders.findIdentity(orderId)
                .orElseThrow(() -> notFound());
        requireExistingCounterAccess(identity.counterId(), actor);
        long expectedVersion = expectedVersion(ifMatch);
        try {
            lifecycle.transition(orderId, expectedVersion, nextStatus, actor.userId(), expiryReason);
        } catch (OrderVersionConflictException exception) {
            StaffOrder currentOrder = currentStaffOrder(orderId, identity.counterId());
            throw new ApiProblemException(HttpStatus.PRECONDITION_FAILED, ApiErrorCode.PRECONDITION_FAILED,
                    "This order has changed. Review the current order and retry with its latest ETag.",
                    null, currentOrder);
        } catch (InvalidOrderTransitionException exception) {
            throw new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.INVALID_ORDER_TRANSITION,
                    exception.getMessage());
        } catch (OrderNotFoundException exception) {
            throw notFound();
        } catch (IllegalArgumentException exception) {
            throw new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR,
                    exception.getMessage());
        }
        return currentStaffOrder(orderId, identity.counterId());
    }

    public StaffOrder redeemPickupToken(UUID counterId, String code, XeatsPrincipal actor) {
        requireExistingCounterAccess(counterId, actor);
        if (!redemptionAttempts.isAllowed(actor.userId(), counterId)) {
            throw new ApiProblemException(HttpStatus.TOO_MANY_REQUESTS, ApiErrorCode.RATE_LIMITED,
                    "Too many unsuccessful pickup-token attempts. Try again in one minute.");
        }
        try {
            OrderRecord collected = lifecycle.collectWithPickupCode(counterId, code, actor.userId());
            redemptionAttempts.succeeded(actor.userId(), counterId);
            return currentStaffOrder(collected.id(), counterId);
        } catch (InvalidPickupTokenException exception) {
            redemptionAttempts.failed(actor.userId(), counterId);
            throw new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY,
                    ApiErrorCode.INVALID_OR_UNAVAILABLE_TOKEN, exception.getMessage());
        }
    }

    private StaffCounter staffCounter(CounterSummary counter, Instant now) {
        CounterData context = capacity.findCounter(counter.id()).orElse(null);
        List<QueueEntry> queue = project(staffOrders.findOrdersForCounter(counter.id()), now);
        int accepted = count(queue, OrderStatus.ACCEPTED);
        int preparing = count(queue, OrderStatus.PREPARING);
        int ready = count(queue, OrderStatus.READY);
        int due = (int) queue.stream().filter(entry -> isDue(entry.order(), now)).count();
        int overdue = (int) queue.stream().filter(QueueEntry::overdue).count();
        PickupWindow nextWindow = queue.stream()
                .filter(entry -> entry.order().status().isActive())
                .filter(entry -> !entry.order().pickupWindowEndAt().isBefore(now))
                .min(Comparator.comparing((QueueEntry entry) -> entry.order().pickupWindowStartAt())
                        .thenComparing(entry -> entry.order().id()))
                .map(entry -> new PickupWindow(entry.order().pickupSlotId(),
                        entry.order().pickupWindowStartAt(), entry.order().pickupWindowEndAt()))
                .orElse(null);
        boolean openNow = context != null && isOpenNow(context, now);
        return new StaffCounter(counter.id(), counter.vendorId(), counter.name(), counter.pickupInstructions(),
                counter.active(), counter.acceptingOnlineOrders(), counter.item(), openNow,
                new QueueCounts(accepted, preparing, ready, due, overdue), nextWindow);
    }

    private boolean isOpenNow(CounterData context, Instant now) {
        if (!context.counter().active() || !context.vendorActive() || !context.courtActive()) {
            return false;
        }
        OpeningHours hours = capacity.findOpeningHours(context.counter().id(), context.timeZone());
        ZonedDateTime local = now.atZone(ZoneId.of(context.timeZone()));
        DayOfWeek today = local.getDayOfWeek();
        DayOfWeek yesterday = today.minus(1);
        LocalTime time = local.toLocalTime();
        for (OpeningInterval interval : hours.intervals()) {
            if (interval.closesAt().isAfter(interval.opensAt())) {
                if (interval.day() == today && !time.isBefore(interval.opensAt())
                        && time.isBefore(interval.closesAt())) {
                    return true;
                }
            } else if (interval.day() == today && !time.isBefore(interval.opensAt())
                    || interval.day() == yesterday && time.isBefore(interval.closesAt())) {
                return true;
            }
        }
        return false;
    }

    private List<QueueEntry> project(List<StaffQueueOrder> rows, Instant now) {
        Map<UUID, StaffQueueOrder> staffRows = rows.stream().collect(
                java.util.stream.Collectors.toMap(row -> row.order().id(), row -> row));
        List<ReadinessOrder> workload = rows.stream().map(row -> new ReadinessOrder(
                row.order(), row.onlineCapacity(), row.minimumPreparationLeadSeconds(), row.serviceSecondsPerMeal()))
                .toList();
        return readiness.project(workload, now).stream().map(projected -> {
            StaffQueueOrder source = staffRows.get(projected.order().id());
            return new QueueEntry(source, projected.estimatedReadyAt(), projected.estimateIsApproximate(),
                    isOverdue(source.order(), now));
        }).toList();
    }

    private StaffOrder currentStaffOrder(UUID orderId, UUID counterId) {
        return project(staffOrders.findOrdersForCounter(counterId), clock.instant()).stream()
                .filter(entry -> entry.order().id().equals(orderId))
                .findFirst()
                .map(this::toStaffOrder)
                .orElseThrow(StaffOrderService::notFound);
    }

    private StaffOrder toStaffOrder(QueueEntry entry) {
        OrderRecord order = entry.order();
        return new StaffOrder(
                order.id(), order.counterId(), order.counterName(), order.pickupInstructions(), order.itemName(),
                new com.xeats.backend.catalog.discovery.DiscoveryDtos.Money(
                        order.itemPriceMinor(), order.itemCurrency()),
                new PickupWindow(order.pickupSlotId(), order.pickupWindowStartAt(), order.pickupWindowEndAt()),
                entry.estimatedReadyAt(), entry.estimateIsApproximate(), null,
                OrderReadinessProjection.ONSITE_WAIT_UNAVAILABLE_REASON,
                order.status(), order.version(), order.placedAt(), order.acceptedAt(),
                order.preparingAt(), order.readyAt(), order.collectedAt(), order.expiredAt(), order.expiredReason(),
                entry.overdue(), order.simulatedPaymentStatus(), entry.source().employeeDisplayLabel());
    }

    private void requireExistingCounterAccess(UUID counterId, XeatsPrincipal actor) {
        if (staffOrders.findCounter(counterId).isEmpty()) {
            throw notFound();
        }
        authorization.requireCounterOperationAccess(actor, counterId);
    }

    private long expectedVersion(String ifMatch) {
        Matcher matcher = ifMatch == null ? null : STRONG_VERSION.matcher(ifMatch);
        if (matcher == null || !matcher.matches()) {
            throw invalid("If-Match must contain the quoted current order version.");
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw invalid("If-Match contains an invalid order version.");
        }
    }

    private static int count(List<QueueEntry> queue, OrderStatus status) {
        return (int) queue.stream().filter(entry -> entry.order().status() == status).count();
    }

    private static boolean isOverdue(OrderRecord order, Instant now) {
        return order.status().isActive() && !order.pickupWindowEndAt().isAfter(now);
    }

    private static boolean isDue(OrderRecord order, Instant now) {
        return order.status().isActive() && !order.pickupWindowStartAt().isAfter(now)
                && order.pickupWindowEndAt().isAfter(now);
    }

    private static ApiProblemException notFound() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, ApiErrorCode.NOT_FOUND,
                "The requested resource was not found.");
    }

    private static ApiProblemException invalid(String detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_ERROR, detail);
    }

    private record QueueEntry(
            StaffQueueOrder source, Instant estimatedReadyAt, boolean estimateIsApproximate, boolean overdue) {
        private OrderRecord order() {
            return source.order();
        }
    }
}
