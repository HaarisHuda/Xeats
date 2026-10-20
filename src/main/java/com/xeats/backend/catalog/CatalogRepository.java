package com.xeats.backend.catalog;

import java.sql.Time;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence operations for catalog fixtures and the catalog services built in Phase 2. */
@Repository
public class CatalogRepository {
    private final JdbcTemplate jdbc;

    public CatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(FoodCourt foodCourt) {
        jdbc.update("""
                INSERT INTO food_court (id, code, name, time_zone, active)
                VALUES (UUID_TO_BIN(?), ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE code = VALUES(code), name = VALUES(name),
                    time_zone = VALUES(time_zone), active = VALUES(active)
                """, foodCourt.id().toString(), foodCourt.code(), foodCourt.name(),
                foodCourt.timeZone(), foodCourt.active());
    }

    public void upsert(Vendor vendor) {
        jdbc.update("""
                INSERT INTO vendor (id, food_court_id, name, service_model, active)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?)
                ON DUPLICATE KEY UPDATE food_court_id = VALUES(food_court_id),
                    name = VALUES(name), service_model = VALUES(service_model), active = VALUES(active)
                """, vendor.id().toString(), vendor.foodCourtId().toString(), vendor.name(),
                vendor.serviceModel().name(), vendor.active());
    }

    public void upsert(Counter counter) {
        jdbc.update("""
                INSERT INTO counter (id, vendor_id, name, pickup_instructions, active, accepting_online_orders)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE vendor_id = VALUES(vendor_id), name = VALUES(name),
                    pickup_instructions = VALUES(pickup_instructions), active = VALUES(active),
                    accepting_online_orders = VALUES(accepting_online_orders)
                """, counter.id().toString(), counter.vendorId().toString(), counter.name(),
                counter.pickupInstructions(), counter.active(), counter.acceptingOnlineOrders());
    }

    public void upsert(MenuItem item) {
        jdbc.update("""
                INSERT INTO menu_item (id, counter_id, name, description, category, price_minor, currency, availability)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE counter_id = VALUES(counter_id), name = VALUES(name),
                    description = VALUES(description), category = VALUES(category),
                    price_minor = VALUES(price_minor), currency = VALUES(currency),
                    availability = VALUES(availability)
                """, item.id().toString(), item.counterId().toString(), item.name(), item.description(),
                item.category(), item.priceMinor(), item.currency(), item.availability().name());
    }

    public void upsert(CounterOpeningInterval interval) {
        jdbc.update("""
                INSERT INTO counter_opening_interval (counter_id, iso_weekday, opens_at, closes_at)
                VALUES (UUID_TO_BIN(?), ?, ?, ?)
                ON DUPLICATE KEY UPDATE closes_at = VALUES(closes_at)
                """, interval.counterId().toString(), interval.weekday().getValue(),
                Time.valueOf(interval.opensAt()), Time.valueOf(interval.closesAt()));
    }
}
