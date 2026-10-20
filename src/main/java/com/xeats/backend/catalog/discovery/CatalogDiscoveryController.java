package com.xeats.backend.catalog.discovery;

import java.util.UUID;

import com.xeats.backend.catalog.discovery.DiscoveryDtos.CounterSummary;
import com.xeats.backend.catalog.discovery.DiscoveryDtos.FoodCourt;
import com.xeats.backend.catalog.discovery.DiscoveryDtos.Items;
import com.xeats.backend.catalog.discovery.DiscoveryDtos.Vendor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CatalogDiscoveryController {
    private final CatalogDiscoveryService discovery;

    public CatalogDiscoveryController(CatalogDiscoveryService discovery) {
        this.discovery = discovery;
    }

    @GetMapping("/food-courts")
    public Items<FoodCourt> foodCourts() {
        return new Items<>(discovery.foodCourts());
    }

    @GetMapping("/food-courts/{foodCourtId}/vendors")
    public Items<Vendor> vendors(@PathVariable UUID foodCourtId) {
        return new Items<>(discovery.vendors(foodCourtId));
    }

    @GetMapping("/vendors/{vendorId}/counters")
    public Items<CounterSummary> counters(@PathVariable UUID vendorId) {
        return new Items<>(discovery.counters(vendorId));
    }

    @GetMapping("/counters/{counterId}")
    public CounterSummary counter(@PathVariable UUID counterId) {
        return discovery.counter(counterId);
    }
}
