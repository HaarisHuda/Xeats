package com.xeats.backend.capacity;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.Valid;

import com.xeats.backend.catalog.management.CatalogManagementDtos.CapacityPolicyRequest;
import com.xeats.backend.capacity.CapacityDtos.CapacityPolicy;
import com.xeats.backend.capacity.CapacityDtos.CounterCapacityStatus;
import com.xeats.backend.capacity.CapacityDtos.OrderOptions;
import com.xeats.backend.identity.XeatsPrincipal;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CapacityController {
    private final CapacityService capacity;

    public CapacityController(CapacityService capacity) {
        this.capacity = capacity;
    }

    @GetMapping("/counters/{counterId}/order-options")
    public OrderOptions orderOptions(
            @PathVariable UUID counterId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return capacity.orderOptions(counterId, from, to);
    }

    @GetMapping("/staff/counters/{counterId}/capacity")
    public CounterCapacityStatus staffCapacity(
            @PathVariable UUID counterId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            Authentication authentication) {
        return capacity.staffCapacity(counterId, from, to, (XeatsPrincipal) authentication.getPrincipal());
    }

    @PutMapping("/staff/counters/{counterId}/capacity-policy")
    public CapacityPolicy updatePolicy(
            @PathVariable UUID counterId,
            @Valid @RequestBody CapacityPolicyRequest request,
            Authentication authentication) {
        return capacity.updatePolicy(counterId, request, (XeatsPrincipal) authentication.getPrincipal());
    }
}
