package com.xeats.backend.catalog.management;

import java.sql.Time;
import java.sql.Timestamp;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import com.xeats.backend.catalog.MenuItemAvailability;
import com.xeats.backend.catalog.discovery.DiscoveryDtos.CounterSummary;
import com.xeats.backend.catalog.discovery.DiscoveryDtos.FoodCourt;
import com.xeats.backend.catalog.discovery.DiscoveryDtos.MenuItem;
import com.xeats.backend.catalog.discovery.DiscoveryDtos.Money;
import com.xeats.backend.catalog.discovery.DiscoveryDtos.Vendor;
import com.xeats.backend.catalog.management.CatalogManagementDtos.CapacityPolicyRequest;
import com.xeats.backend.catalog.management.CatalogManagementDtos.OpeningInterval;
import com.xeats.backend.catalog.management.CatalogManagementDtos.OpeningIntervalRequest;
import com.xeats.backend.catalog.management.CatalogManagementDtos.OpeningHours;
import com.xeats.backend.catalog.management.CatalogManagementDtos.UpdateMenuItemRequest;
import com.xeats.backend.catalog.VendorServiceModel;
import com.xeats.backend.common.persistence.CounterRowLockRepository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogManagementRepository {
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
                    MenuItemAvailability.valueOf(result.getString("availability"))));

    private static final RowMapper<com.xeats.backend.catalog.MenuItem> CATALOG_ITEM_MAPPER = (result, row) ->
            new com.xeats.backend.catalog.MenuItem(
                    UUID.fromString(result.getString("id")),
                    UUID.fromString(result.getString("counter_id")),
                    result.getString("name"),
                    result.getString("description"),
                    result.getString("category"),
                    result.getLong("price_minor"),
                    result.getString("currency"),
                    MenuItemAvailability.valueOf(result.getString("availability")));

    private final JdbcTemplate jdbc;
    private final CounterRowLockRepository counterLocks;

    public CatalogManagementRepository(JdbcTemplate jdbc, CounterRowLockRepository counterLocks) {
        this.jdbc = jdbc;
        this.counterLocks = counterLocks;
    }

    public boolean foodCourtCodeExists(String code) {
        return exists("SELECT EXISTS(SELECT 1 FROM food_court WHERE code = ?)", code);
    }

    public Optional<FoodCourt> findActiveFoodCourt(UUID id) {
        List<FoodCourt> matches = jdbc.query("""
                SELECT BIN_TO_UUID(id) AS id, code, name, time_zone
                FROM food_court WHERE id = UUID_TO_BIN(?) AND active = TRUE
                """, (result, row) -> new FoodCourt(
                UUID.fromString(result.getString("id")), result.getString("code"),
                result.getString("name"), result.getString("time_zone")), id.toString());
        return matches.stream().findFirst();
    }

    public Optional<Vendor> findActiveVendor(UUID id) {
        List<Vendor> matches = jdbc.query("""
                SELECT BIN_TO_UUID(v.id) AS id, BIN_TO_UUID(v.food_court_id) AS food_court_id, v.name
                FROM vendor v JOIN food_court f ON f.id = v.food_court_id
                WHERE v.id = UUID_TO_BIN(?) AND v.active = TRUE AND f.active = TRUE
                """, (result, row) -> new Vendor(
                UUID.fromString(result.getString("id")), UUID.fromString(result.getString("food_court_id")),
                result.getString("name")), id.toString());
        return matches.stream().findFirst();
    }

    public Optional<String> findActiveFoodCourtTimeZoneForVendor(UUID vendorId) {
        List<String> zones = jdbc.query("""
                SELECT f.time_zone FROM food_court f
                JOIN vendor v ON v.food_court_id = f.id
                WHERE v.id = UUID_TO_BIN(?) AND v.active = TRUE AND f.active = TRUE
                """, (result, row) -> result.getString("time_zone"), vendorId.toString());
        return zones.stream().findFirst();
    }

    public void insertFoodCourt(UUID id, String code, String name, String timeZone) {
        jdbc.update("""
                INSERT INTO food_court (id, code, name, time_zone, active)
                VALUES (UUID_TO_BIN(?), ?, ?, ?, TRUE)
                """, id.toString(), code, name, timeZone);
    }

    public void insertVendor(UUID id, UUID foodCourtId, String name, VendorServiceModel serviceModel) {
        jdbc.update("""
                INSERT INTO vendor (id, food_court_id, name, service_model, active)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, TRUE)
                """, id.toString(), foodCourtId.toString(), name, serviceModel.name());
    }

    public void insertCounter(UUID id, UUID vendorId, String name, String pickupInstructions) {
        jdbc.update("""
                INSERT INTO counter (id, vendor_id, name, pickup_instructions, active, accepting_online_orders)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, TRUE, TRUE)
                """, id.toString(), vendorId.toString(), name, pickupInstructions);
    }

    public void insertMenuItem(UUID id, UUID counterId, String name, String description, String category,
            long priceMinor) {
        jdbc.update("""
                INSERT INTO menu_item (id, counter_id, name, description, category, price_minor, currency, availability)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, ?, 'INR', 'AVAILABLE')
                """, id.toString(), counterId.toString(), name, description, category, priceMinor);
    }

    public void insertCapacityPolicy(UUID counterId, CapacityPolicyRequest policy, Timestamp effectiveAt) {
        jdbc.update("""
                INSERT INTO counter_capacity_policy (
                    counter_id, revision, effective_at, slot_duration_minutes, total_meals_per_slot,
                    walk_in_reserve_percent, minimum_preparation_lead_seconds, maximum_advance_minutes,
                    service_seconds_per_meal)
                VALUES (UUID_TO_BIN(?), 1, ?, ?, ?, ?, ?, ?, ?)
                """, counterId.toString(), effectiveAt, policy.slotDurationMinutes(), policy.totalMealsPerSlot(),
                policy.walkInReservePercent(), policy.minimumPreparationLeadSeconds(),
                policy.maximumAdvanceMinutes(), policy.serviceSecondsPerMeal());
    }

    public int findLatestCapacitySlotDuration(UUID counterId) {
        return jdbc.queryForObject("""
                SELECT slot_duration_minutes
                FROM counter_capacity_policy
                WHERE counter_id = UUID_TO_BIN(?)
                ORDER BY revision DESC LIMIT 1
                """, Integer.class, counterId.toString());
    }

    public void replaceOpeningIntervals(UUID counterId, List<OpeningIntervalRequest> intervals) {
        jdbc.update("DELETE FROM counter_opening_interval WHERE counter_id = UUID_TO_BIN(?)", counterId.toString());
        for (OpeningIntervalRequest interval : intervals) {
            jdbc.update("""
                    INSERT INTO counter_opening_interval (counter_id, iso_weekday, opens_at, closes_at)
                    VALUES (UUID_TO_BIN(?), ?, ?, ?)
                    """, counterId.toString(), interval.day().getValue(),
                    Time.valueOf(interval.opensAt()), Time.valueOf(interval.closesAt()));
        }
    }

    public OpeningHours findOpeningHours(UUID counterId, String timeZone) {
        List<OpeningInterval> intervals = jdbc.query("""
                SELECT iso_weekday, opens_at, closes_at
                FROM counter_opening_interval
                WHERE counter_id = UUID_TO_BIN(?)
                ORDER BY iso_weekday, opens_at
                """, (result, row) -> new OpeningInterval(
                DayOfWeek.of(result.getInt("iso_weekday")), result.getTime("opens_at").toLocalTime(),
                result.getTime("closes_at").toLocalTime()), counterId.toString());
        return new OpeningHours(timeZone, intervals);
    }

    public boolean isActiveVendorStaff(UUID userId) {
        return exists("""
                SELECT EXISTS(SELECT 1 FROM app_user u
                    JOIN user_role r ON r.user_id = u.id AND r.role = 'VENDOR_STAFF'
                    WHERE u.id = UUID_TO_BIN(?) AND u.enabled = TRUE)
                """, userId.toString());
    }

    public boolean userExists(UUID userId) {
        return exists("SELECT EXISTS(SELECT 1 FROM app_user WHERE id = UUID_TO_BIN(?))", userId.toString());
    }

    public int insertStaffAssignmentIfMissing(UUID counterId, UUID userId) {
        return jdbc.update("""
                INSERT IGNORE INTO staff_counter_assignment (user_id, counter_id)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?))
                """, userId.toString(), counterId.toString());
    }

    public int removeStaffAssignment(UUID counterId, UUID userId) {
        return jdbc.update("""
                DELETE FROM staff_counter_assignment
                WHERE counter_id = UUID_TO_BIN(?) AND user_id = UUID_TO_BIN(?)
                """, counterId.toString(), userId.toString());
    }

    public boolean lockCounter(UUID counterId) {
        return counterLocks.lockForUpdate(counterId);
    }

    public Optional<CounterSummary> findCounterSummary(UUID counterId) {
        List<CounterSummary> counters = jdbc.query(counterQuery() + "WHERE c.id = UUID_TO_BIN(?)",
                COUNTER_MAPPER, counterId.toString());
        return counters.stream().findFirst();
    }

    public Optional<com.xeats.backend.catalog.MenuItem> findMenuItem(UUID counterId) {
        List<com.xeats.backend.catalog.MenuItem> items = jdbc.query("""
                SELECT BIN_TO_UUID(id) AS id, BIN_TO_UUID(counter_id) AS counter_id, name,
                    description, category, price_minor, currency, availability
                FROM menu_item WHERE counter_id = UUID_TO_BIN(?)
                """, CATALOG_ITEM_MAPPER, counterId.toString());
        return items.stream().findFirst();
    }

    public int updateMenuItem(UUID counterId, UpdateMenuItemRequest update) {
        return jdbc.update("""
                UPDATE menu_item SET name = ?, description = ?, category = ?, price_minor = ?
                WHERE counter_id = UUID_TO_BIN(?)
                """, update.name().trim(), cleanOptional(update.description()), cleanOptional(update.category()),
                update.price().amountMinor(), counterId.toString());
    }

    public int updateAvailability(UUID counterId, MenuItemAvailability availability) {
        return jdbc.update("UPDATE menu_item SET availability = ? WHERE counter_id = UUID_TO_BIN(?)",
                availability.name(), counterId.toString());
    }

    public int updateOnlineAdmission(UUID counterId, boolean acceptingOnlineOrders) {
        return jdbc.update("UPDATE counter SET accepting_online_orders = ? WHERE id = UUID_TO_BIN(?)",
                acceptingOnlineOrders, counterId.toString());
    }

    public int updateActive(UUID counterId, boolean active) {
        return jdbc.update("UPDATE counter SET active = ? WHERE id = UUID_TO_BIN(?)", active, counterId.toString());
    }

    public String findCounterTimeZone(UUID counterId) {
        List<String> zones = jdbc.query("""
                SELECT f.time_zone FROM food_court f
                JOIN vendor v ON v.food_court_id = f.id
                JOIN counter c ON c.vendor_id = v.id
                WHERE c.id = UUID_TO_BIN(?)
                """, (result, row) -> result.getString("time_zone"), counterId.toString());
        return zones.stream().findFirst().orElseThrow();
    }

    public long insertChangeEvent(UUID counterId, UUID actorId, String fieldName,
            String previousJson, String newJson, Timestamp occurredAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO counter_change_event (
                    counter_id, actor_user_id, field_name, previous_value, new_value, occurred_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, CAST(? AS JSON), CAST(? AS JSON), ?)
                """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, counterId.toString());
            statement.setString(2, actorId.toString());
            statement.setString(3, fieldName);
            statement.setString(4, previousJson);
            statement.setString(5, newJson);
            statement.setTimestamp(6, occurredAt);
            return statement;
        }, keyHolder);
        Number id = keyHolder.getKey();
        if (id == null) {
            throw new IllegalStateException("The counter change event did not receive an identity.");
        }
        return id.longValue();
    }

    private boolean exists(String query, Object... args) {
        return Boolean.TRUE.equals(jdbc.queryForObject(query, Boolean.class, args));
    }

    private static String cleanOptional(String value) {
        return value == null ? null : value.trim();
    }

    private String counterQuery() {
        return """
                SELECT BIN_TO_UUID(c.id) AS counter_id, BIN_TO_UUID(c.vendor_id) AS vendor_id,
                    c.name AS counter_name, c.pickup_instructions, c.active AS counter_active,
                    c.accepting_online_orders,
                    BIN_TO_UUID(m.id) AS item_id, m.name AS item_name, m.description AS item_description,
                    m.category AS item_category, m.price_minor, m.currency, m.availability
                FROM counter c
                JOIN vendor v ON v.id = c.vendor_id
                JOIN food_court f ON f.id = v.food_court_id
                JOIN menu_item m ON m.counter_id = c.id
                """;
    }
}
