package com.xeats.backend.capacity;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.xeats.backend.catalog.MenuItemAvailability;
import com.xeats.backend.catalog.discovery.DiscoveryDtos.CounterSummary;
import com.xeats.backend.catalog.discovery.DiscoveryDtos.MenuItem;
import com.xeats.backend.catalog.discovery.DiscoveryDtos.Money;
import com.xeats.backend.catalog.management.CatalogManagementDtos.CapacityPolicyRequest;
import com.xeats.backend.catalog.management.CatalogManagementDtos.OpeningHours;
import com.xeats.backend.catalog.management.CatalogManagementDtos.OpeningInterval;
import com.xeats.backend.capacity.CapacityDtos.CapacityPolicy;
import com.xeats.backend.capacity.PickupSlotPlanner.SlotBounds;
import com.xeats.backend.common.persistence.CounterRowLockRepository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class CapacityRepository {
    private static final RowMapper<CounterData> COUNTER_MAPPER = (result, row) -> new CounterData(
            new CounterSummary(
                    UUID.fromString(result.getString("counter_id")),
                    UUID.fromString(result.getString("vendor_id")),
                    result.getString("counter_name"), result.getString("pickup_instructions"),
                    result.getBoolean("counter_active"), result.getBoolean("accepting_online_orders"),
                    new MenuItem(
                            UUID.fromString(result.getString("item_id")), result.getString("item_name"),
                            result.getString("item_description"), result.getString("item_category"),
                            new Money(result.getLong("price_minor"), result.getString("currency")),
                            MenuItemAvailability.valueOf(result.getString("availability")))),
            result.getString("time_zone"), result.getBoolean("vendor_active"), result.getBoolean("court_active"));

    private static final RowMapper<CapacityPolicy> POLICY_MAPPER = (result, row) -> new CapacityPolicy(
            result.getInt("revision"), result.getInt("slot_duration_minutes"),
            result.getInt("total_meals_per_slot"), result.getInt("walk_in_reserve_percent"),
            PickupSlotPlanner.onlineCapacity(result.getInt("total_meals_per_slot"),
                    result.getInt("walk_in_reserve_percent")),
            result.getInt("minimum_preparation_lead_seconds"), result.getInt("maximum_advance_minutes"),
            result.getInt("service_seconds_per_meal"), result.getTimestamp("effective_at").toInstant());

    private static final RowMapper<StoredSlot> STORED_SLOT_MAPPER = (result, row) -> {
        int totalCapacity = result.getInt("total_capacity_snapshot");
        int onlineCapacity = result.getInt("online_capacity_snapshot");
        CapacityPolicy policy = new CapacityPolicy(
                result.getInt("policy_revision"), result.getInt("slot_duration_minutes"), totalCapacity,
                result.getInt("walk_in_reserve_percent"), onlineCapacity,
                result.getInt("minimum_preparation_lead_seconds"), result.getInt("maximum_advance_minutes"),
                result.getInt("service_seconds_per_meal"), result.getTimestamp("effective_at").toInstant());
        return new StoredSlot(
                UUID.fromString(result.getString("id")), result.getTimestamp("start_at").toInstant(),
                result.getTimestamp("end_at").toInstant(), totalCapacity, onlineCapacity,
                result.getInt("confirmed_allocation_count"), policy);
    };

    private final JdbcTemplate jdbc;
    private final CounterRowLockRepository counterLocks;

    public CapacityRepository(JdbcTemplate jdbc, CounterRowLockRepository counterLocks) {
        this.jdbc = jdbc;
        this.counterLocks = counterLocks;
    }

    public boolean lockCounter(UUID counterId) {
        return counterLocks.lockForUpdate(counterId);
    }

    public Optional<CounterData> findCounter(UUID counterId) {
        List<CounterData> counters = jdbc.query(counterQuery() + " WHERE c.id = UUID_TO_BIN(?)",
                COUNTER_MAPPER, counterId.toString());
        return counters.stream().findFirst();
    }

    public Optional<CapacityPolicy> findLatestPolicy(UUID counterId) {
        List<CapacityPolicy> policies = jdbc.query("""
                SELECT revision, slot_duration_minutes, total_meals_per_slot, walk_in_reserve_percent,
                    minimum_preparation_lead_seconds, maximum_advance_minutes, service_seconds_per_meal,
                    effective_at
                FROM counter_capacity_policy
                WHERE counter_id = UUID_TO_BIN(?)
                ORDER BY revision DESC LIMIT 1
                """, POLICY_MAPPER, counterId.toString());
        return policies.stream().findFirst();
    }

    public OpeningHours findOpeningHours(UUID counterId, String timeZone) {
        List<OpeningInterval> intervals = jdbc.query("""
                SELECT iso_weekday, opens_at, closes_at
                FROM counter_opening_interval
                WHERE counter_id = UUID_TO_BIN(?)
                ORDER BY iso_weekday, opens_at
                """, (result, row) -> new OpeningInterval(DayOfWeek.of(result.getInt("iso_weekday")),
                result.getTime("opens_at").toLocalTime(), result.getTime("closes_at").toLocalTime()),
                counterId.toString());
        return new OpeningHours(timeZone, intervals);
    }

    public void insertSlot(UUID id, UUID counterId, CapacityPolicy policy, SlotBounds slot) {
        jdbc.update("""
                INSERT IGNORE INTO pickup_slot (
                    id, counter_id, policy_revision, start_at, end_at,
                    total_capacity_snapshot, online_capacity_snapshot, confirmed_allocation_count)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?, ?, ?, 0)
                """, id.toString(), counterId.toString(), policy.revision(),
                Timestamp.from(slot.startAt()), Timestamp.from(slot.endAt()), policy.totalMealsPerSlot(),
                policy.onlineCapacityPerSlot());
    }

    public List<StoredSlot> findSlots(UUID counterId, Instant fromInclusive, Instant throughInclusive) {
        if (throughInclusive.isBefore(fromInclusive)) {
            return List.of();
        }
        return jdbc.query("""
                SELECT BIN_TO_UUID(s.id) AS id, s.start_at, s.end_at, s.total_capacity_snapshot,
                    s.online_capacity_snapshot, s.confirmed_allocation_count, p.revision AS policy_revision,
                    p.slot_duration_minutes, p.walk_in_reserve_percent, p.minimum_preparation_lead_seconds,
                    p.maximum_advance_minutes, p.service_seconds_per_meal, p.effective_at
                FROM pickup_slot s
                JOIN counter_capacity_policy p
                    ON p.counter_id = s.counter_id AND p.revision = s.policy_revision
                WHERE s.counter_id = UUID_TO_BIN(?) AND s.start_at >= ? AND s.start_at <= ?
                ORDER BY s.start_at, s.id
                """, STORED_SLOT_MAPPER,
                counterId.toString(), Timestamp.from(fromInclusive), Timestamp.from(throughInclusive));
    }

    public Optional<StoredSlot> findSlotForUpdate(UUID counterId, UUID slotId) {
        List<StoredSlot> slots = jdbc.query("""
                SELECT BIN_TO_UUID(s.id) AS id, s.start_at, s.end_at, s.total_capacity_snapshot,
                    s.online_capacity_snapshot, s.confirmed_allocation_count, p.revision AS policy_revision,
                    p.slot_duration_minutes, p.walk_in_reserve_percent, p.minimum_preparation_lead_seconds,
                    p.maximum_advance_minutes, p.service_seconds_per_meal, p.effective_at
                FROM pickup_slot s
                JOIN counter_capacity_policy p
                    ON p.counter_id = s.counter_id AND p.revision = s.policy_revision
                WHERE s.counter_id = UUID_TO_BIN(?) AND s.id = UUID_TO_BIN(?)
                FOR UPDATE
                """, STORED_SLOT_MAPPER, counterId.toString(), slotId.toString());
        return slots.stream().findFirst();
    }

    public int allocateOnlinePosition(UUID counterId, UUID slotId) {
        return jdbc.update("""
                UPDATE pickup_slot
                SET confirmed_allocation_count = confirmed_allocation_count + 1
                WHERE counter_id = UUID_TO_BIN(?) AND id = UUID_TO_BIN(?)
                    AND confirmed_allocation_count < online_capacity_snapshot
                """, counterId.toString(), slotId.toString());
    }

    public CapacityPolicy insertPolicyRevision(UUID counterId, CapacityPolicyRequest request, Instant effectiveAt) {
        int revision = findLatestPolicy(counterId).orElseThrow().revision() + 1;
        jdbc.update("""
                INSERT INTO counter_capacity_policy (
                    counter_id, revision, effective_at, slot_duration_minutes, total_meals_per_slot,
                    walk_in_reserve_percent, minimum_preparation_lead_seconds, maximum_advance_minutes,
                    service_seconds_per_meal)
                VALUES (UUID_TO_BIN(?), ?, ?, ?, ?, ?, ?, ?, ?)
                """, counterId.toString(), revision, Timestamp.from(effectiveAt), request.slotDurationMinutes(),
                request.totalMealsPerSlot(), request.walkInReservePercent(),
                request.minimumPreparationLeadSeconds(), request.maximumAdvanceMinutes(),
                request.serviceSecondsPerMeal());
        return findLatestPolicy(counterId).orElseThrow();
    }

    public void insertPolicyChangeEvent(
            UUID counterId, UUID actorId, String previousJson, String nextJson, Instant occurredAt) {
        jdbc.update("""
                INSERT INTO counter_change_event (
                    counter_id, actor_user_id, field_name, previous_value, new_value, occurred_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), 'CAPACITY_POLICY', CAST(? AS JSON), CAST(? AS JSON), ?)
                """, counterId.toString(), actorId.toString(), previousJson, nextJson,
                Timestamp.from(occurredAt));
    }

    private String counterQuery() {
        return """
                SELECT BIN_TO_UUID(c.id) AS counter_id, BIN_TO_UUID(c.vendor_id) AS vendor_id,
                    c.name AS counter_name, c.pickup_instructions, c.active AS counter_active,
                    c.accepting_online_orders, BIN_TO_UUID(m.id) AS item_id, m.name AS item_name,
                    m.description AS item_description, m.category AS item_category,
                    m.price_minor, m.currency, m.availability, f.time_zone,
                    v.active AS vendor_active, f.active AS court_active
                FROM counter c
                JOIN vendor v ON v.id = c.vendor_id
                JOIN food_court f ON f.id = v.food_court_id
                JOIN menu_item m ON m.counter_id = c.id
                """;
    }

    public record CounterData(
            CounterSummary counter, String timeZone, boolean vendorActive, boolean courtActive) { }

    public record StoredSlot(
            UUID id,
            Instant startAt,
            Instant endAt,
            int totalCapacity,
            int onlineCapacity,
            int confirmedAllocations,
            CapacityPolicy policy) { }
}
