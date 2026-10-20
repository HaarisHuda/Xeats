package com.xeats.backend.ordering;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;

import com.xeats.backend.identity.XeatsPrincipal;
import com.xeats.backend.ordering.OrderResponseDtos.ConfirmationResponse;
import com.xeats.backend.ordering.OrderResponseDtos.EmployeeOrder;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class OrderConfirmationController {
    private final OrderConfirmationService orders;

    public OrderConfirmationController(OrderConfirmationService orders) {
        this.orders = orders;
    }

    @PostMapping("/orders")
    public ResponseEntity<EmployeeOrder> createOrder(
            @Valid @RequestBody CreateOrderRequest request,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            Authentication authentication) {
        XeatsPrincipal employee = (XeatsPrincipal) authentication.getPrincipal();
        ConfirmationResponse confirmation = orders.confirm(request, idempotencyKey, employee.userId());
        HttpHeaders headers = new HttpHeaders();
        headers.setETag("\"%d\"".formatted(confirmation.order().version()));
        headers.set("Idempotency-Replayed", Boolean.toString(confirmation.replayed()));
        headers.setLocation(URI.create("/api/v1/me/orders/" + confirmation.order().id()));
        return ResponseEntity.status(confirmation.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .headers(headers)
                .body(confirmation.order());
    }
}
