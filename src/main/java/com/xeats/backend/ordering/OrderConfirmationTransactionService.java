package com.xeats.backend.ordering;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.xeats.backend.capacity.CapacityRepository;
import com.xeats.backend.capacity.CapacityService;
import com.xeats.backend.capacity.CapacityService.OrderSelection;
import com.xeats.backend.catalog.discovery.DiscoveryDtos.CounterSummary;
import com.xeats.backend.common.persistence.CounterRowLockRepository;
import com.xeats.backend.common.web.ApiErrorCode;
import com.xeats.backend.common.web.ApiProblemException;
import com.xeats.backend.events.LiveEventPublisher;
import com.xeats.backend.ordering.OrderPersistenceModels.IdempotencyRecord;
import com.xeats.backend.ordering.OrderPersistenceModels.OrderRecord;
import com.xeats.backend.ordering.OrderResponseDtos.ConfirmationOutcome;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderConfirmationTransactionService {
    private static final int TOKEN_GENERATION_ATTEMPTS = 5;

    private final CounterRowLockRepository counterLocks;
    private final OrderPersistenceRepository orders;
    private final CapacityRepository capacityRepository;
    private final CapacityService capacity;
    private final PickupTokenGenerator tokenGenerator;
    private final Clock clock;
    private final LiveEventPublisher liveEvents;

    public OrderConfirmationTransactionService(
            CounterRowLockRepository counterLocks,
            OrderPersistenceRepository orders,
            CapacityRepository capacityRepository,
            CapacityService capacity,
            PickupTokenGenerator tokenGenerator,
            Clock clock,
            LiveEventPublisher liveEvents) {
        this.counterLocks = counterLocks;
        this.orders = orders;
        this.capacityRepository = capacityRepository;
        this.capacity = capacity;
        this.tokenGenerator = tokenGenerator;
        this.clock = clock;
        this.liveEvents = liveEvents;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ConfirmationOutcome confirm(
            CreateOrderRequest request, UUID idempotencyKey, String fingerprint, UUID employeeId) {
        if (!counterLocks.lockForUpdate(request.counterId())) {
            throw new ApiProblemException(HttpStatus.NOT_FOUND, ApiErrorCode.NOT_FOUND,
                    "The requested resource was not found.");
        }

        IdempotencyRecord existing = orders.findIdempotencyRecord(employeeId, idempotencyKey).orElse(null);
        if (existing != null) {
            return replay(existing, fingerprint);
        }

        OrderSelection selection = capacity.prepareOrderSelection(request.counterId(), request.pickupSlotId());
        if (orders.hasActiveOrderClaim(employeeId, request.counterId())) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ApiErrorCode.ACTIVE_ORDER_EXISTS,
                    "You already have an active order at this counter.");
        }

        CounterSummary counter = selection.counter();
        Instant now = clock.instant();
        UUID orderId = UUID.randomUUID();
        OrderRecord order = new OrderRecord(
                orderId,
                employeeId,
                counter.id(),
                counter.item().id(),
                selection.slot().id(),
                counter.item().name(),
                counter.item().price().amountMinor(),
                counter.item().price().currency(),
                counter.name(),
                counter.pickupInstructions(),
                selection.slot().startAt(),
                selection.slot().endAt(),
                OrderStatus.ACCEPTED,
                1,
                "SIMULATED_SUCCEEDED",
                now,
                now,
                null,
                null,
                null,
                null,
                null);

        orders.insertOrder(order);
        issueUniqueToken(orderId, now);
        orders.createActiveOrderClaim(employeeId, counter.id(), orderId, now);
        if (capacityRepository.allocateOnlinePosition(counter.id(), selection.slot().id()) != 1) {
            throw slotUnavailable(request);
        }
        orders.appendStatusEvent(orderId, 1, null, OrderStatus.ACCEPTED, employeeId, now, null);
        orders.insertIdempotencyRecord(new IdempotencyRecord(
                employeeId, OrderIdempotencyOperation.CREATE_ORDER, idempotencyKey, fingerprint,
                orderId, 201, now));
        liveEvents.counterChanged(com.xeats.backend.events.LiveEventType.SLOT_CAPACITY_CHANGED,
                selection.slot().id(), counter.id(), selection.slot().confirmedAllocations() + 1, now);
        liveEvents.orderStatusChanged(orderId, employeeId, counter.id(), 1, now);
        return new ConfirmationOutcome(orderId, false);
    }

    private void issueUniqueToken(UUID orderId, Instant now) {
        for (int attempt = 0; attempt < TOKEN_GENERATION_ATTEMPTS; attempt++) {
            try {
                orders.issuePickupToken(orderId, tokenGenerator.nextCode(), now);
                return;
            } catch (DuplicateKeyException exception) {
                if (attempt + 1 == TOKEN_GENERATION_ATTEMPTS) {
                    throw new IllegalStateException("Could not allocate a unique pickup token.", exception);
                }
            }
        }
        throw new IllegalStateException("Could not allocate a unique pickup token.");
    }

    private ApiProblemException slotUnavailable(CreateOrderRequest request) {
        return new ApiProblemException(HttpStatus.CONFLICT, ApiErrorCode.SLOT_UNAVAILABLE,
                "That pickup window is no longer available. Choose one of the refreshed options.",
                capacity.orderOptions(request.counterId(), null, null));
    }

    private static ConfirmationOutcome replay(IdempotencyRecord existing, String fingerprint) {
        if (!existing.requestFingerprintSha256().equalsIgnoreCase(fingerprint)) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ApiErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "This idempotency key was already used for a different order request.");
        }
        return new ConfirmationOutcome(existing.orderId(), true);
    }
}
