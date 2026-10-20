package com.xeats.backend.ordering;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import com.xeats.backend.identity.XeatsPrincipal;
import com.xeats.backend.ordering.StaffOrderDtos.StaffCounters;
import com.xeats.backend.ordering.StaffOrderDtos.StaffOrder;
import com.xeats.backend.ordering.StaffOrderDtos.StaffOrderPage;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class StaffOrderController {
    private final StaffOrderService orders;

    public StaffOrderController(StaffOrderService orders) {
        this.orders = orders;
    }

    @GetMapping("/staff/counters")
    public StaffCounters listAssignedCounters(Authentication authentication) {
        return orders.listAssignedCounters((XeatsPrincipal) authentication.getPrincipal());
    }

    @GetMapping("/staff/counters/{counterId}/orders")
    public StaffOrderPage listCounterOrders(
            @PathVariable UUID counterId,
            @RequestParam(name = "status", required = false) List<OrderStatus> statuses,
            @RequestParam(required = false) Instant pickupWindowStart,
            @RequestParam(required = false) Boolean overdue,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            Authentication authentication) {
        return orders.listCounterOrders(counterId, statuses, pickupWindowStart, overdue, page, size,
                (XeatsPrincipal) authentication.getPrincipal());
    }

    @PostMapping("/staff/orders/{orderId}/preparing")
    public ResponseEntity<StaffOrder> beginPreparing(
            @PathVariable UUID orderId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            Authentication authentication) {
        return transition(orderId, OrderStatus.PREPARING, ifMatch, null, authentication);
    }

    @PostMapping("/staff/orders/{orderId}/ready")
    public ResponseEntity<StaffOrder> markReady(
            @PathVariable UUID orderId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            Authentication authentication) {
        return transition(orderId, OrderStatus.READY, ifMatch, null, authentication);
    }

    @PostMapping("/staff/orders/{orderId}/expiry")
    public ResponseEntity<StaffOrder> expire(
            @PathVariable UUID orderId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody ExpireOrderRequest request,
            Authentication authentication) {
        return transition(orderId, OrderStatus.EXPIRED, ifMatch, request.reason(), authentication);
    }

    @PostMapping("/staff/counters/{counterId}/pickup-redemptions")
    public ResponseEntity<StaffOrder> redeemPickupToken(
            @PathVariable UUID counterId,
            @Valid @RequestBody RedeemPickupTokenRequest request,
            Authentication authentication) {
        StaffOrder collected = orders.redeemPickupToken(counterId, request.code(),
                (XeatsPrincipal) authentication.getPrincipal());
        HttpHeaders headers = new HttpHeaders();
        headers.setETag("\"%d\"".formatted(collected.version()));
        return ResponseEntity.ok().headers(headers).body(collected);
    }

    private ResponseEntity<StaffOrder> transition(
            UUID orderId, OrderStatus nextStatus, String ifMatch, String reason, Authentication authentication) {
        StaffOrder updated = orders.transition(orderId, nextStatus, ifMatch, reason,
                (XeatsPrincipal) authentication.getPrincipal());
        HttpHeaders headers = new HttpHeaders();
        headers.setETag("\"%d\"".formatted(updated.version()));
        return ResponseEntity.ok().headers(headers).body(updated);
    }
}
