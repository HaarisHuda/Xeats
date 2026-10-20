package com.xeats.backend.ordering;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.xeats.backend.catalog.discovery.DiscoveryDtos.Money;
import com.xeats.backend.capacity.CapacityDtos.PickupWindow;
import com.xeats.backend.capacity.OrderReadinessProjection;
import com.xeats.backend.capacity.OrderReadinessProjection.ProjectedOrder;
import com.xeats.backend.common.web.ApiErrorCode;
import com.xeats.backend.common.web.ApiProblemException;
import com.xeats.backend.ordering.OrderPersistenceModels.IdempotencyRecord;
import com.xeats.backend.ordering.OrderPersistenceModels.OrderRecord;
import com.xeats.backend.ordering.OrderPersistenceModels.OrderWithToken;
import com.xeats.backend.ordering.OrderPersistenceModels.PickupToken;
import com.xeats.backend.ordering.OrderResponseDtos.ConfirmationOutcome;
import com.xeats.backend.ordering.OrderResponseDtos.ConfirmationResponse;
import com.xeats.backend.ordering.OrderResponseDtos.EmployeeOrder;
import com.xeats.backend.ordering.OrderResponseDtos.PickupTokenView;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class OrderConfirmationService {
    private static final int MAX_TRANSACTION_ATTEMPTS = 3;

    private final OrderPersistenceRepository orders;
    private final OrderConfirmationTransactionService transaction;
    private final OrderReadinessProjection readiness;
    private final Clock clock;
    public OrderConfirmationService(
            OrderPersistenceRepository orders,
            OrderConfirmationTransactionService transaction,
            OrderReadinessProjection readiness,
            Clock clock) {
        this.orders = orders;
        this.transaction = transaction;
        this.readiness = readiness;
        this.clock = clock;
    }

    public ConfirmationResponse confirm(CreateOrderRequest request, UUID key, UUID employeeId) {
        String fingerprint = fingerprint(request);
        Optional<IdempotencyRecord> existing = orders.findIdempotencyRecord(employeeId, key);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }

        for (int attempt = 1; attempt <= MAX_TRANSACTION_ATTEMPTS; attempt++) {
            try {
                ConfirmationOutcome outcome = transaction.confirm(request, key, fingerprint, employeeId);
                return response(outcome.orderId(), outcome.replayed());
            } catch (DuplicateKeyException exception) {
                Optional<IdempotencyRecord> winner = orders.findIdempotencyRecord(employeeId, key);
                if (winner.isPresent()) {
                    return replay(winner.get(), fingerprint);
                }
                if (orders.hasActiveOrderClaim(employeeId, request.counterId())) {
                    throw activeOrderExists();
                }
                throw exception;
            } catch (TransientDataAccessException exception) {
                if (attempt == MAX_TRANSACTION_ATTEMPTS) {
                    throw exception;
                }
                backoff(attempt);
            }
        }
        throw new IllegalStateException("Order confirmation retry loop ended unexpectedly.");
    }

    private ConfirmationResponse replay(IdempotencyRecord existing, String fingerprint) {
        if (!MessageDigest.isEqual(
                existing.requestFingerprintSha256().getBytes(StandardCharsets.US_ASCII),
                fingerprint.getBytes(StandardCharsets.US_ASCII))) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ApiErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "This idempotency key was already used for a different order request.");
        }
        return response(existing.orderId(), true);
    }

    private ConfirmationResponse response(UUID orderId, boolean replayed) {
        OrderWithToken stored = orders.findByIdWithToken(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        ProjectedOrder projected = readiness.projectByOrderId(
                orders.findReadinessOrdersForCounter(stored.order().counterId()),
                clock.instant()).get(orderId);
        return new ConfirmationResponse(toCreationResponse(stored.order(), stored.token(), projected), replayed);
    }

    private EmployeeOrder toCreationResponse(OrderRecord order, PickupToken token, ProjectedOrder projected) {
        return new EmployeeOrder(
                order.id(),
                order.counterId(),
                order.counterName(),
                order.pickupInstructions(),
                order.itemName(),
                new Money(order.itemPriceMinor(), order.itemCurrency()),
                new PickupWindow(order.pickupSlotId(), order.pickupWindowStartAt(), order.pickupWindowEndAt()),
                projected == null ? null : projected.estimatedReadyAt(),
                projected != null && projected.estimateIsApproximate(),
                null,
                OrderReadinessProjection.ONSITE_WAIT_UNAVAILABLE_REASON,
                OrderStatus.ACCEPTED,
                1,
                order.placedAt(),
                order.acceptedAt(),
                null,
                null,
                null,
                null,
                null,
                false,
                order.simulatedPaymentStatus(),
                new PickupTokenView(token.code(), true));
    }

    private static String fingerprint(CreateOrderRequest request) {
        String canonicalJson = "{\"counterId\":\"%s\",\"pickupSlotId\":\"%s\"}"
                .formatted(request.counterId(), request.pickupSlotId());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available in this runtime.", exception);
        }
    }

    private static ApiProblemException activeOrderExists() {
        return new ApiProblemException(HttpStatus.CONFLICT, ApiErrorCode.ACTIVE_ORDER_EXISTS,
                "You already have an active order at this counter.");
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(attempt * 10L + ThreadLocalRandom.current().nextLong(20L));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying order confirmation.", exception);
        }
    }
}
