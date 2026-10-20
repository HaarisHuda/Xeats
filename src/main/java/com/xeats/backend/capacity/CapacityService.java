package com.xeats.backend.capacity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.xeats.backend.catalog.MenuItemAvailability;
import com.xeats.backend.catalog.management.CatalogManagementDtos.CapacityPolicyRequest;
import com.xeats.backend.capacity.CapacityDtos.CapacityPolicy;
import com.xeats.backend.capacity.CapacityDtos.CounterCapacityStatus;
import com.xeats.backend.capacity.CapacityDtos.OrderOptions;
import com.xeats.backend.capacity.CapacityDtos.SelectableWindow;
import com.xeats.backend.capacity.CapacityDtos.StaffCapacityWindow;
import com.xeats.backend.capacity.CapacityDtos.UnavailableWindow;
import com.xeats.backend.capacity.CapacityRepository.CounterData;
import com.xeats.backend.capacity.CapacityRepository.StoredSlot;
import com.xeats.backend.capacity.PickupSlotPlanner.Estimate;
import com.xeats.backend.capacity.PickupSlotPlanner.SlotBounds;
import com.xeats.backend.common.web.ApiErrorCode;
import com.xeats.backend.common.web.ApiProblemException;
import com.xeats.backend.events.LiveEventPublisher;
import com.xeats.backend.events.LiveEventType;
import com.xeats.backend.identity.IdentityAuthorizationService;
import com.xeats.backend.identity.XeatsPrincipal;
import com.xeats.backend.ordering.OrderPersistenceModels.ReadinessOrder;
import com.xeats.backend.ordering.OrderPersistenceRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CapacityService {
    private final CapacityRepository repository;
    private final OrderPersistenceRepository orders;
    private final OrderReadinessProjection readiness;
    private final IdentityAuthorizationService authorization;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final LiveEventPublisher liveEvents;

    public CapacityService(
            CapacityRepository repository,
            OrderPersistenceRepository orders,
            OrderReadinessProjection readiness,
            IdentityAuthorizationService authorization,
            ObjectMapper objectMapper,
            Clock clock,
            LiveEventPublisher liveEvents) {
        this.repository = repository;
        this.orders = orders;
        this.readiness = readiness;
        this.authorization = authorization;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.liveEvents = liveEvents;
    }

    @Transactional
    public OrderOptions orderOptions(UUID counterId, Instant requestedFrom, Instant requestedTo) {
        Context context = lockAndLoad(counterId);
        Instant now = clock.instant();
        CapacityPolicy policy = context.policy();
        Instant horizon = PickupSlotPlanner.horizon(now, policy);
        Instant from = later(now, requestedFrom == null ? now : requestedFrom);
        Instant through = earlier(horizon, requestedTo == null ? horizon : requestedTo);
        validateRange(requestedFrom, requestedTo);

        String unavailableReason = unavailableCounterReason(context);
        if (unavailableReason != null) {
            return new OrderOptions(context.counter(), context.timeZone(), List.of(), List.of(), null,
                    unavailableReason);
        }

        List<SlotBounds> scheduledSlots = PickupSlotPlanner.generate(
                context.openingHours(), policy, from, through);
        Set<Instant> currentOpeningStarts = scheduledSlots.stream()
                .map(SlotBounds::startAt).collect(Collectors.toSet());
        if (!through.isBefore(from)) {
            materialize(context, scheduledSlots);
        }
        List<StoredSlot> slots = repository.findSlots(counterId, from, through).stream()
                .filter(slot -> currentOpeningStarts.contains(slot.startAt())).toList();
        List<ReadinessOrder> workload = orders.findReadinessOrdersForCounter(counterId);
        List<SelectableWindow> selectable = new ArrayList<>();
        List<UnavailableWindow> unavailable = new ArrayList<>();
        for (StoredSlot slot : slots) {
            int earlierUnready = readiness.unreadyBefore(workload, slot.startAt(), true);
            String reason = unavailableSlotReason(slot, now, horizon, earlierUnready);
            if (reason == null) {
                Estimate estimate = PickupSlotPlanner.estimate(bounds(slot), slot.policy(), now,
                        slot.confirmedAllocations(), earlierUnready);
                selectable.add(new SelectableWindow(slot.id(), slot.startAt(), slot.endAt(),
                        slot.onlineCapacity(), slot.confirmedAllocations(),
                        Math.max(0, slot.onlineCapacity() - slot.confirmedAllocations()),
                        estimate.estimatedReadyAt(), true, null,
                        "WALK_IN_AND_HANDOVER_TIMES_NOT_MEASURED"));
            } else {
                unavailable.add(new UnavailableWindow(slot.id(), slot.startAt(), slot.endAt(), reason));
            }
        }
        UUID recommendation = selectable.isEmpty() ? null : selectable.getFirst().id();
        String noOptionsReason = recommendation == null
                ? noOptionsReason(context, policy, slots)
                : null;
        return new OrderOptions(context.counter(), context.timeZone(), selectable, unavailable,
                recommendation, noOptionsReason);
    }

    /**
     * Rechecks a displayed slot while holding the shared counter lock, then locks that slot row for order
     * allocation. The returned quote and slot are from the same transaction as the eventual order write.
     */
    @Transactional
    public OrderSelection prepareOrderSelection(UUID counterId, UUID slotId) {
        if (!repository.lockCounter(counterId)) {
            throw notFound();
        }
        CounterData counter = repository.findCounter(counterId).orElseThrow(CapacityService::notFound);
        if (!counter.counter().active() || !counter.vendorActive() || !counter.courtActive()) {
            throw conflict(ApiErrorCode.COUNTER_CLOSED, "This counter is currently closed.");
        }
        if (!counter.counter().acceptingOnlineOrders()) {
            throw conflict(ApiErrorCode.ORDERING_PAUSED, "This counter is not accepting online orders.");
        }
        if (counter.counter().item().availability() == MenuItemAvailability.SOLD_OUT) {
            throw conflict(ApiErrorCode.ITEM_SOLD_OUT, "This item is currently sold out.");
        }

        OrderOptions refreshedOptions = orderOptions(counterId, null, null);
        StoredSlot selectedSlot = repository.findSlotForUpdate(counterId, slotId).orElse(null);
        SelectableWindow selectedWindow = refreshedOptions.selectableWindows().stream()
                .filter(window -> window.id().equals(slotId))
                .findFirst()
                .orElse(null);
        if (selectedSlot == null || selectedWindow == null) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ApiErrorCode.SLOT_UNAVAILABLE,
                    "That pickup window is no longer available. Choose one of the refreshed options.",
                    refreshedOptions);
        }
        return new OrderSelection(counter.counter(), selectedSlot, selectedWindow.estimatedReadyAt());
    }

    @Transactional
    public CounterCapacityStatus staffCapacity(
            UUID counterId, Instant requestedFrom, Instant requestedTo, XeatsPrincipal actor) {
        authorization.requireCounterOperationAccess(actor, counterId);
        Context context = lockAndLoad(counterId);
        Instant now = clock.instant();
        Instant horizon = PickupSlotPlanner.horizon(now, context.policy());
        Instant from = later(now, requestedFrom == null ? now : requestedFrom);
        Instant maxRange = from.plus(Duration.ofHours(24));
        Instant through = earlier(horizon, earlier(maxRange, requestedTo == null ? maxRange : requestedTo));
        validateRange(requestedFrom, requestedTo);
        if (requestedTo != null && requestedTo.isAfter(maxRange)) {
            through = earlier(through, maxRange);
        }
        List<SlotBounds> scheduledSlots = unavailableCounterReason(context) == null
                ? PickupSlotPlanner.generate(context.openingHours(), context.policy(), from, through)
                : List.of();
        Set<Instant> currentOpeningStarts = scheduledSlots.stream()
                .map(SlotBounds::startAt).collect(Collectors.toSet());
        if (!through.isBefore(from)) {
            materialize(context, scheduledSlots);
        }

        List<StaffCapacityWindow> windows = new ArrayList<>();
        List<ReadinessOrder> workload = orders.findReadinessOrdersForCounter(counterId);
        List<OrderReadinessProjection.ProjectedOrder> projected = readiness.project(workload, now);
        for (StoredSlot slot : repository.findSlots(counterId, from, through)) {
            int earlierUnready = readiness.unreadyBefore(workload, slot.startAt(), false);
            int unreadyIncludingSlot = readiness.unreadyBefore(workload, slot.startAt(), true);
            boolean selectable = currentOpeningStarts.contains(slot.startAt())
                    && unavailableCounterReason(context) == null
                    && unavailableSlotReason(slot, now, horizon, unreadyIncludingSlot) == null;
            OrderReadinessProjection.ProjectedOrder latestProjection = projected.stream()
                    .filter(entry -> entry.order().pickupSlotId().equals(slot.id()))
                    .filter(entry -> entry.order().status().isActive())
                    .filter(entry -> entry.estimatedReadyAt() != null)
                    .max(java.util.Comparator.comparing(OrderReadinessProjection.ProjectedOrder::estimatedReadyAt)
                            .thenComparing(OrderReadinessProjection.ProjectedOrder::estimateIsApproximate))
                    .orElse(null);
            windows.add(new StaffCapacityWindow(slot.id(), slot.startAt(), slot.endAt(),
                    slot.totalCapacity(), slot.totalCapacity() - slot.onlineCapacity(), slot.onlineCapacity(),
                    slot.confirmedAllocations(),
                    Math.max(0, slot.onlineCapacity() - slot.confirmedAllocations()), earlierUnready,
                    latestProjection == null ? null : latestProjection.estimatedReadyAt(),
                    latestProjection != null && latestProjection.estimateIsApproximate(), null,
                    OrderReadinessProjection.ONSITE_WAIT_UNAVAILABLE_REASON, selectable));
        }
        return new CounterCapacityStatus(counterId, context.timeZone(), context.policy(), windows);
    }

    @Transactional
    public CapacityPolicy updatePolicy(UUID counterId, CapacityPolicyRequest request, XeatsPrincipal actor) {
        authorization.requireCounterOperationAccess(actor, counterId);
        Context context = lockAndLoad(counterId);
        validatePolicy(request, context.openingHours());
        CapacityPolicy previous = context.policy();
        if (samePolicy(previous, request)) {
            return previous;
        }
        Instant effectiveAt = clock.instant();
        CapacityPolicy next = repository.insertPolicyRevision(counterId, request, effectiveAt);
        repository.insertPolicyChangeEvent(counterId, actor.userId(), json(previous), json(next), effectiveAt);
        liveEvents.counterChanged(LiveEventType.SLOT_CAPACITY_CHANGED, counterId, counterId,
                next.revision(), effectiveAt);
        return next;
    }

    private Context lockAndLoad(UUID counterId) {
        if (!repository.lockCounter(counterId)) {
            throw notFound();
        }
        CounterData counter = repository.findCounter(counterId).orElseThrow(CapacityService::notFound);
        if (!counter.counter().active() || !counter.vendorActive() || !counter.courtActive()) {
            throw notFound();
        }
        CapacityPolicy policy = repository.findLatestPolicy(counterId).orElseThrow(CapacityService::notFound);
        return new Context(counter.counter(), counter.timeZone(), policy,
                repository.findOpeningHours(counterId, counter.timeZone()));
    }

    private void materialize(Context context, List<SlotBounds> slots) {
        for (SlotBounds slot : slots) {
            repository.insertSlot(UUID.randomUUID(), context.counter().id(), context.policy(), slot);
        }
    }

    private String unavailableCounterReason(Context context) {
        if (!context.counter().acceptingOnlineOrders()) {
            return "ORDERING_PAUSED";
        }
        if (context.counter().item().availability() == MenuItemAvailability.SOLD_OUT) {
            return "ITEM_SOLD_OUT";
        }
        return null;
    }

    private String unavailableSlotReason(
            StoredSlot slot, Instant now, Instant currentHorizon, int earlierUnready) {
        CapacityPolicy snapshot = slot.policy();
        Instant horizon = earlier(currentHorizon, PickupSlotPlanner.horizon(now, snapshot));
        if (slot.onlineCapacity() == 0) {
            return "NO_ONLINE_CAPACITY";
        }
        if (slot.confirmedAllocations() >= slot.onlineCapacity()) {
            return "WINDOW_FULL";
        }
        if (slot.startAt().isBefore(now.plusSeconds(snapshot.minimumPreparationLeadSeconds()))) {
            return "OUTSIDE_PREPARATION_LEAD";
        }
        if (slot.startAt().isAfter(horizon)
                || !PickupSlotPlanner.selectable(bounds(slot), snapshot, now,
                        slot.confirmedAllocations(), earlierUnready, horizon)) {
            return "PREPARATION_BACKLOG";
        }
        return null;
    }

    private String noOptionsReason(Context context, CapacityPolicy policy, List<StoredSlot> slots) {
        String counterReason = unavailableCounterReason(context);
        if (counterReason != null) {
            return counterReason;
        }
        if (policy.onlineCapacityPerSlot() == 0 && slots.isEmpty() || (!slots.isEmpty()
                && slots.stream().allMatch(slot -> slot.onlineCapacity() == 0))) {
            return "NO_ONLINE_CAPACITY";
        }
        return slots.isEmpty() ? "COUNTER_CLOSED" : "NO_FEASIBLE_WINDOW";
    }

    private void validatePolicy(CapacityPolicyRequest policy,
            com.xeats.backend.catalog.management.CatalogManagementDtos.OpeningHours openingHours) {
        if (policy.slotDurationMinutes() % 5 != 0) {
            throw invalid("Slot duration must be one of 5, 10, 15, 20, 25 or 30 minutes.");
        }
        int slotSeconds = policy.slotDurationMinutes() * 60;
        for (com.xeats.backend.catalog.management.CatalogManagementDtos.OpeningInterval interval
                : openingHours.intervals()) {
            int duration = interval.closesAt().toSecondOfDay() - interval.opensAt().toSecondOfDay();
            if (duration <= 0) {
                duration += 24 * 60 * 60;
            }
            if (duration % slotSeconds != 0) {
                throw invalid("Opening intervals must end on a slot-duration boundary for this policy.");
            }
        }
    }

    private void validateRange(Instant from, Instant to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw invalid("The end of the requested range must not be before its start.");
        }
    }

    private boolean samePolicy(CapacityPolicy current, CapacityPolicyRequest requested) {
        return current.slotDurationMinutes() == requested.slotDurationMinutes()
                && current.totalMealsPerSlot() == requested.totalMealsPerSlot()
                && current.walkInReservePercent() == requested.walkInReservePercent()
                && current.minimumPreparationLeadSeconds() == requested.minimumPreparationLeadSeconds()
                && current.maximumAdvanceMinutes() == requested.maximumAdvanceMinutes()
                && current.serviceSecondsPerMeal() == requested.serviceSecondsPerMeal();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize the capacity policy audit record.", exception);
        }
    }

    private static SlotBounds bounds(StoredSlot slot) {
        return new SlotBounds(slot.startAt(), slot.endAt());
    }

    private static Instant later(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    private static Instant earlier(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private static ApiProblemException notFound() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, ApiErrorCode.NOT_FOUND,
                "The requested resource was not found.");
    }

    private static ApiProblemException invalid(String detail) {
        return new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, detail);
    }

    private static ApiProblemException conflict(ApiErrorCode code, String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, code, detail);
    }

    private record Context(
            com.xeats.backend.catalog.discovery.DiscoveryDtos.CounterSummary counter,
            String timeZone,
            CapacityPolicy policy,
            com.xeats.backend.catalog.management.CatalogManagementDtos.OpeningHours openingHours) { }

    public record OrderSelection(
            com.xeats.backend.catalog.discovery.DiscoveryDtos.CounterSummary counter,
            StoredSlot slot,
            Instant estimatedReadyAt) { }
}
