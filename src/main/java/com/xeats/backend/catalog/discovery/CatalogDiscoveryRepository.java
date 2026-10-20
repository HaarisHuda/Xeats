package com.xeats.backend.catalog.discovery;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.xeats.backend.catalog.discovery.DiscoveryDtos.CounterSummary;
import com.xeats.backend.catalog.discovery.DiscoveryDtos.FoodCourt;
import com.xeats.backend.catalog.discovery.DiscoveryDtos.MenuItem;
import com.xeats.backend.catalog.discovery.DiscoveryDtos.Money;
import com.xeats.backend.catalog.discovery.DiscoveryDtos.Vendor;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogDiscoveryRepository {
    private static final RowMapper<CounterSummary> COUNTER_MAPPER = (result, row) -> new CounterSummary(
            UUID.fromString(result.getString("counter_id")),
            UUID.fromString(result.getString("vendor_id")),
            result.getString("counter_name"),
            result.getString("pickup_instructions"),
            result.getBoolean("counter_active"),
            result.getBoolean("accepting_online_orders"),
            new MenuItem(
                    UUID.fromString(result.getString("item_id")),
                    result.getString("item_name"),
                    result.getString("item_description"),
                    result.getString("item_category"),
                    new Money(result.getLong("price_minor"), result.getString("currency")),
                    com.xeats.backend.catalog.MenuItemAvailability.valueOf(result.getString("availability"))));

    private final JdbcTemplate jdbc;

    public CatalogDiscoveryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<FoodCourt> findActiveFoodCourts() {
        return jdbc.query("""
                SELECT BIN_TO_UUID(id) AS id, code, name, time_zone
                FROM food_court
                WHERE active = TRUE
                ORDER BY code, id
                """, (result, row) -> new FoodCourt(
                UUID.fromString(result.getString("id")), result.getString("code"),
                result.getString("name"), result.getString("time_zone")));
    }

    public List<Vendor> findActiveVendors(UUID foodCourtId) {
        return jdbc.query("""
                SELECT BIN_TO_UUID(v.id) AS id, BIN_TO_UUID(v.food_court_id) AS food_court_id, v.name
                FROM vendor v
                JOIN food_court f ON f.id = v.food_court_id
                WHERE v.food_court_id = UUID_TO_BIN(?) AND v.active = TRUE AND f.active = TRUE
                ORDER BY v.name, v.id
                """, (result, row) -> new Vendor(
                UUID.fromString(result.getString("id")), UUID.fromString(result.getString("food_court_id")),
                result.getString("name")), foodCourtId.toString());
    }

    public List<CounterSummary> findActiveCounters(UUID vendorId) {
        return jdbc.query(counterQuery() + """
                WHERE c.vendor_id = UUID_TO_BIN(?) AND c.active = TRUE
                    AND v.active = TRUE AND f.active = TRUE
                ORDER BY c.name, c.id
                """, COUNTER_MAPPER, vendorId.toString());
    }

    public Optional<CounterSummary> findActiveCounter(UUID counterId) {
        List<CounterSummary> counters = jdbc.query(counterQuery() + """
                WHERE c.id = UUID_TO_BIN(?) AND c.active = TRUE
                    AND v.active = TRUE AND f.active = TRUE
                """, COUNTER_MAPPER, counterId.toString());
        return counters.stream().findFirst();
    }

    private String counterQuery() {
        return """
                SELECT BIN_TO_UUID(c.id) AS counter_id,
                    BIN_TO_UUID(c.vendor_id) AS vendor_id,
                    c.name AS counter_name, c.pickup_instructions, c.active AS counter_active,
                    c.accepting_online_orders,
                    BIN_TO_UUID(m.id) AS item_id, m.name AS item_name,
                    m.description AS item_description, m.category AS item_category,
                    m.price_minor, m.currency, m.availability
                FROM counter c
                JOIN vendor v ON v.id = c.vendor_id
                JOIN food_court f ON f.id = v.food_court_id
                JOIN menu_item m ON m.counter_id = c.id
                """;
    }
}
