package com.xeats.backend.catalog.management;

import java.util.UUID;

import jakarta.validation.Valid;

import com.xeats.backend.catalog.discovery.DiscoveryDtos.CounterSummary;
import com.xeats.backend.catalog.discovery.DiscoveryDtos.MenuItem;
import com.xeats.backend.catalog.management.CatalogManagementDtos.AvailabilityRequest;
import com.xeats.backend.catalog.management.CatalogManagementDtos.OpeningHours;
import com.xeats.backend.catalog.management.CatalogManagementDtos.OpeningHoursRequest;
import com.xeats.backend.catalog.management.CatalogManagementDtos.OnlineAdmissionRequest;
import com.xeats.backend.catalog.management.CatalogManagementDtos.UpdateMenuItemRequest;
import com.xeats.backend.identity.XeatsPrincipal;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/staff/counters/{counterId}")
public class CounterOperationsController {
    private final CatalogManagementService management;

    public CounterOperationsController(CatalogManagementService management) {
        this.management = management;
    }

    @PatchMapping("/item")
    public MenuItem updateItem(
            @PathVariable UUID counterId,
            @Valid @RequestBody UpdateMenuItemRequest request,
            Authentication authentication) {
        return management.updateItem(counterId, request, principal(authentication));
    }

    @PutMapping("/availability")
    public MenuItem setAvailability(
            @PathVariable UUID counterId,
            @Valid @RequestBody AvailabilityRequest request,
            Authentication authentication) {
        return management.setAvailability(counterId, request, principal(authentication));
    }

    @PutMapping("/ordering")
    public CounterSummary setOnlineAdmission(
            @PathVariable UUID counterId,
            @Valid @RequestBody OnlineAdmissionRequest request,
            Authentication authentication) {
        return management.setOnlineAdmission(counterId, request, principal(authentication));
    }

    @PutMapping("/opening-hours")
    public OpeningHours replaceOpeningHours(
            @PathVariable UUID counterId,
            @Valid @RequestBody OpeningHoursRequest request,
            Authentication authentication) {
        return management.replaceOpeningHours(counterId, request, principal(authentication));
    }

    private static XeatsPrincipal principal(Authentication authentication) {
        return (XeatsPrincipal) authentication.getPrincipal();
    }
}
