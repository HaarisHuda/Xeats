package com.xeats.backend.catalog.discovery;

import java.util.List;
import java.util.UUID;

import com.xeats.backend.catalog.MenuItemAvailability;

/** Public employee discovery responses; persistence models and staff fields stay internal. */
public final class DiscoveryDtos {
    private DiscoveryDtos() { }

    public record Items<T>(List<T> items) {
        public Items {
            items = List.copyOf(items);
        }
    }

    public record FoodCourt(UUID id, String code, String name, String timeZone) { }

    public record Vendor(UUID id, UUID foodCourtId, String name) { }

    public record Money(long amountMinor, String currency) { }

    public record MenuItem(
            UUID id,
            String name,
            String description,
            String category,
            Money price,
            MenuItemAvailability availability) { }

    public record CounterSummary(
            UUID id,
            UUID vendorId,
            String name,
            String pickupInstructions,
            boolean active,
            boolean acceptingOnlineOrders,
            MenuItem item) { }
}
