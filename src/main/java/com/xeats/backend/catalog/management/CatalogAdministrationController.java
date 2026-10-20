package com.xeats.backend.catalog.management;

import java.util.UUID;

import jakarta.validation.Valid;

import com.xeats.backend.catalog.discovery.DiscoveryDtos.CounterSummary;
import com.xeats.backend.catalog.discovery.DiscoveryDtos.FoodCourt;
import com.xeats.backend.catalog.discovery.DiscoveryDtos.Vendor;
import com.xeats.backend.catalog.management.CatalogManagementDtos.CounterActiveRequest;
import com.xeats.backend.catalog.management.CatalogManagementDtos.CreateCounterRequest;
import com.xeats.backend.catalog.management.CatalogManagementDtos.CreateFoodCourtRequest;
import com.xeats.backend.catalog.management.CatalogManagementDtos.CreateVendorRequest;
import com.xeats.backend.catalog.management.CatalogManagementDtos.StaffAssignmentRequest;
import com.xeats.backend.identity.XeatsPrincipal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class CatalogAdministrationController {
    private final CatalogManagementService management;

    public CatalogAdministrationController(CatalogManagementService management) {
        this.management = management;
    }

    @PostMapping("/food-courts")
    public ResponseEntity<FoodCourt> createFoodCourt(@Valid @RequestBody CreateFoodCourtRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(management.createFoodCourt(request));
    }

    @PostMapping("/food-courts/{foodCourtId}/vendors")
    public ResponseEntity<Vendor> createVendor(
            @PathVariable UUID foodCourtId, @Valid @RequestBody CreateVendorRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(management.createVendor(foodCourtId, request));
    }

    @PostMapping("/vendors/{vendorId}/counters")
    public ResponseEntity<CounterSummary> createCounter(
            @PathVariable UUID vendorId,
            @Valid @RequestBody CreateCounterRequest request,
            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                management.createCounter(vendorId, request, principal(authentication)));
    }

    @PostMapping("/counters/{counterId}/staff")
    public ResponseEntity<Void> assignStaff(
            @PathVariable UUID counterId,
            @Valid @RequestBody StaffAssignmentRequest request,
            Authentication authentication) {
        management.assignStaff(counterId, request, principal(authentication));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/counters/{counterId}/staff/{userId}")
    public ResponseEntity<Void> removeStaff(
            @PathVariable UUID counterId,
            @PathVariable UUID userId,
            Authentication authentication) {
        management.removeStaff(counterId, userId, principal(authentication));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/counters/{counterId}/active")
    public CounterSummary setCounterActive(
            @PathVariable UUID counterId,
            @Valid @RequestBody CounterActiveRequest request,
            Authentication authentication) {
        return management.setCounterActive(counterId, request.active(), principal(authentication));
    }

    private static XeatsPrincipal principal(Authentication authentication) {
        return (XeatsPrincipal) authentication.getPrincipal();
    }
}
