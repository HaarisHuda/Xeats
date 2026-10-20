package com.xeats.backend.catalog.discovery;

import java.util.List;
import java.util.UUID;

import com.xeats.backend.catalog.discovery.DiscoveryDtos.CounterSummary;
import com.xeats.backend.catalog.discovery.DiscoveryDtos.FoodCourt;
import com.xeats.backend.catalog.discovery.DiscoveryDtos.Vendor;
import com.xeats.backend.common.web.ApiErrorCode;
import com.xeats.backend.common.web.ApiProblemException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class CatalogDiscoveryService {
    private final CatalogDiscoveryRepository discovery;

    public CatalogDiscoveryService(CatalogDiscoveryRepository discovery) {
        this.discovery = discovery;
    }

    public List<FoodCourt> foodCourts() {
        return discovery.findActiveFoodCourts();
    }

    public List<Vendor> vendors(UUID foodCourtId) {
        return discovery.findActiveVendors(foodCourtId);
    }

    public List<CounterSummary> counters(UUID vendorId) {
        return discovery.findActiveCounters(vendorId);
    }

    public CounterSummary counter(UUID counterId) {
        return discovery.findActiveCounter(counterId)
                .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, ApiErrorCode.NOT_FOUND,
                        "The requested resource was not found."));
    }
}
