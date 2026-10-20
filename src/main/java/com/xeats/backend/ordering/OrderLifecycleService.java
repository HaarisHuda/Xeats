package com.xeats.backend.ordering;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.xeats.backend.common.persistence.CounterRowLockRepository;
import com.xeats.backend.events.LiveEventPublisher;
import com.xeats.backend.ordering.OrderPersistenceModels.ActiveOrderClaim;
import com.xeats.backend.ordering.OrderPersistenceModels.OrderRecord;
import com.xeats.backend.ordering.OrderPersistenceModels.PickupToken;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lock-serialized order state changes used by authorized counter-staff actions. */
@Service
public class OrderLifecycleService {
    private static final int MAX_EXPIRY_REASON_LENGTH = 500;

    private final CounterRowLockRepository counterLocks;
    private final OrderPersistenceRepository orders;
    private final Clock clock;
    private final LiveEventPublisher liveEvents;

    public OrderLifecycleService(
            CounterRowLockRepository counterLocks, OrderPersistenceRepository orders, Clock clock,
            LiveEventPublisher liveEvents) {
        this.counterLocks = counterLocks;
        this.orders = orders;
        this.clock = clock;
        this.liveEvents = liveEvents;
    }

    @Transactional
    public OrderRecord transition(
            UUID orderId,
            long expectedVersion,
            OrderStatus nextStatus,
            UUID actorUserId,
            String expiryReason) {
        return transitionLocked(orderId, expectedVersion, null, null, nextStatus, actorUserId, expiryReason);
    }

    /** Validates and collects a ready order by token inside the same counter-serialized transaction. */
    @Transactional
    public OrderRecord collectWithPickupCode(UUID counterId, String pickupCode, UUID actorUserId) {
        Objects.requireNonNull(counterId, "counterId");
        Objects.requireNonNull(pickupCode, "pickupCode");
        Objects.requireNonNull(actorUserId, "actorUserId");

        OrderPersistenceRepository.PickupRedemptionIdentity redemption = orders
                .findPickupRedemptionIdentity(pickupCode)
                .orElseThrow(InvalidPickupTokenException::new);
        if (!redemption.counterId().equals(counterId)) {
            throw new InvalidPickupTokenException();
        }
        return transitionLocked(redemption.orderId(), null, counterId, pickupCode,
                OrderStatus.COLLECTED, actorUserId, null);
    }

    private OrderRecord transitionLocked(
            UUID orderId,
            Long expectedVersion,
            UUID expectedCounterId,
            String pickupCode,
            OrderStatus nextStatus,
            UUID actorUserId,
            String expiryReason) {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(nextStatus, "nextStatus");
        Objects.requireNonNull(actorUserId, "actorUserId");

        OrderPersistenceRepository.OrderIdentity identity = orders.findIdentity(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        if (!counterLocks.lockForUpdate(identity.counterId())) {
            throw new OrderNotFoundException(orderId);
        }
        OrderRecord current = orders.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        if (!current.counterId().equals(identity.counterId()) || !current.employeeId().equals(identity.employeeId())) {
            throw new IllegalStateException("An order's immutable owner or counter changed during locking.");
        }
        if (expectedCounterId != null && !current.counterId().equals(expectedCounterId)) {
            throw new InvalidPickupTokenException();
        }
        if (expectedVersion != null && expectedVersion != current.version()) {
            throw new OrderVersionConflictException(expectedVersion, current.version());
        }
        if (pickupCode != null && current.status() != OrderStatus.READY) {
            throw new InvalidPickupTokenException();
        }
        OrderStateMachine.requireTransition(current.status(), nextStatus);

        String normalizedReason = validateExpiryReason(nextStatus, expiryReason);
        Instant now = clock.instant();
        PickupToken token = orders.lockPickupToken(orderId)
                .orElseThrow(() -> new IllegalStateException("Every order must have one pickup token."));
        if (pickupCode != null && !token.code().equals(pickupCode)) {
            throw new InvalidPickupTokenException();
        }
        if (!token.isRedeemable()) {
            if (pickupCode != null) {
                throw new InvalidPickupTokenException();
            }
            throw new IllegalStateException("An active order must have a redeemable pickup token.");
        }
        ActiveOrderClaim claim = orders.lockActiveClaimByOrder(orderId)
                .orElseThrow(() -> new IllegalStateException("Every active order must hold its counter claim."));
        if (!claim.employeeId().equals(current.employeeId()) || !claim.counterId().equals(current.counterId())) {
            throw new IllegalStateException("The active-order claim does not match its order.");
        }

        if (nextStatus.isTerminal()) {
            if (orders.markTokenTerminal(orderId, nextStatus, now) != 1) {
                throw new IllegalStateException("The pickup token was already closed.");
            }
        }
        long versionToUpdate = expectedVersion == null ? current.version() : expectedVersion;
        if (orders.updateStatus(orderId, versionToUpdate, nextStatus, now, normalizedReason) != 1) {
            throw new OrderVersionConflictException(versionToUpdate, current.version());
        }
        orders.appendStatusEvent(orderId, current.version() + 1, current.status(), nextStatus,
                actorUserId, now, normalizedReason);
        if (nextStatus.isTerminal()
                && orders.releaseActiveClaim(orderId, current.employeeId(), current.counterId()) != 1) {
            throw new IllegalStateException("A terminal transition must release exactly one active-order claim.");
        }
        OrderRecord updated = orders.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        liveEvents.orderStatusChanged(updated.id(), updated.employeeId(), updated.counterId(), updated.version(), now);
        return updated;
    }

    private static String validateExpiryReason(OrderStatus nextStatus, String reason) {
        if (nextStatus != OrderStatus.EXPIRED) {
            if (reason != null && !reason.isBlank()) {
                throw new IllegalArgumentException("An expiry reason is only valid when expiring an order.");
            }
            return null;
        }
        if (reason == null || reason.trim().length() < 3 || reason.trim().length() > MAX_EXPIRY_REASON_LENGTH) {
            throw new IllegalArgumentException("Expiry requires a reason of 3 to 500 non-whitespace characters.");
        }
        return reason.trim();
    }
}
