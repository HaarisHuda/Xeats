package com.xeats.backend.ordering;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.xeats.backend.catalog.MenuItemAvailability;
import com.xeats.backend.catalog.discovery.DiscoveryDtos.CounterSummary;
import com.xeats.backend.catalog.discovery.DiscoveryDtos.MenuItem;
import com.xeats.backend.catalog.discovery.DiscoveryDtos.Money;
import com.xeats.backend.ordering.OrderPersistenceModels.OrderRecord;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StaffOrderRepository {
    private final JdbcTemplate jdbc;
    private final OrderPersistenceRepository orders;

    public StaffOrderRepository(JdbcTemplate jdbc, OrderPersistenceRepository orders) {
        this.jdbc = jdbc;
        this.orders = orders;
    }

    public List<CounterSummary> findCounters(UUID staffId, boolean includeAll) {
        String assignmentJoin = includeAll
                ? ""
                : " JOIN staff_counter_assignment a ON a.counter_id = c.id";
        String assignmentFilter = includeAll ? "" : " WHERE a.user_id = UUID_TO_BIN(?)";
        Object[] parameters = includeAll ? new Object[0] : new Object[] {staffId.toString()};
        return jdbc.query(counterQuery() + assignmentJoin + assignmentFilter + " ORDER BY c.name, c.id",
                (result, row) -> new CounterSummary(
                        UUID.fromString(result.getString("counter_id")),
                        UUID.fromString(result.getString("vendor_id")), result.getString("counter_name"),
                        result.getString("pickup_instructions"), result.getBoolean("counter_active"),
                        result.getBoolean("accepting_online_orders"),
                        new MenuItem(
                                UUID.fromString(result.getString("item_id")), result.getString("item_name"),
                                result.getString("item_description"), result.getString("item_category"),
                                new Money(result.getLong("price_minor"), result.getString("currency")),
                                MenuItemAvailability.valueOf(result.getString("availability")))),
                parameters);
    }

    public Optional<CounterSummary> findCounter(UUID counterId) {
        List<CounterSummary> counters = jdbc.query(counterQuery() + " WHERE c.id = UUID_TO_BIN(?)",
                (result, row) -> new CounterSummary(
                        UUID.fromString(result.getString("counter_id")),
                        UUID.fromString(result.getString("vendor_id")), result.getString("counter_name"),
                        result.getString("pickup_instructions"), result.getBoolean("counter_active"),
                        result.getBoolean("accepting_online_orders"),
                        new MenuItem(
                                UUID.fromString(result.getString("item_id")), result.getString("item_name"),
                                result.getString("item_description"), result.getString("item_category"),
                                new Money(result.getLong("price_minor"), result.getString("currency")),
                                MenuItemAvailability.valueOf(result.getString("availability")))),
                counterId.toString());
        return counters.stream().findFirst();
    }

    /** Loads full counter history so queue ETA positions retain previously collected/expired allocations. */
    public List<StaffQueueOrder> findOrdersForCounter(UUID counterId) {
        return jdbc.query(orders.staffOrderSelect() + """
                WHERE o.counter_id = UUID_TO_BIN(?)
                """, (result, row) -> new StaffQueueOrder(
                OrderPersistenceRepository.ORDER_MAPPER.mapRow(result, row),
                result.getString("employee_display_label"),
                result.getInt("online_capacity_snapshot"),
                result.getInt("minimum_preparation_lead_seconds"),
                result.getInt("service_seconds_per_meal")), counterId.toString());
    }

    public Optional<StaffQueueOrder> findOrder(UUID orderId) {
        List<StaffQueueOrder> matches = jdbc.query(orders.staffOrderSelect() + """
                WHERE o.id = UUID_TO_BIN(?)
                """, (result, row) -> new StaffQueueOrder(
                OrderPersistenceRepository.ORDER_MAPPER.mapRow(result, row),
                result.getString("employee_display_label"),
                result.getInt("online_capacity_snapshot"),
                result.getInt("minimum_preparation_lead_seconds"),
                result.getInt("service_seconds_per_meal")), orderId.toString());
        return matches.stream().findFirst();
    }

    private String counterQuery() {
        return """
                SELECT BIN_TO_UUID(c.id) AS counter_id, BIN_TO_UUID(c.vendor_id) AS vendor_id,
                    c.name AS counter_name, c.pickup_instructions, c.active AS counter_active,
                    c.accepting_online_orders, BIN_TO_UUID(m.id) AS item_id, m.name AS item_name,
                    m.description AS item_description, m.category AS item_category,
                    m.price_minor, m.currency, m.availability
                FROM counter c
                JOIN vendor v ON v.id = c.vendor_id
                JOIN food_court f ON f.id = v.food_court_id
                JOIN menu_item m ON m.counter_id = c.id
                """;
    }

    public record StaffQueueOrder(
            OrderRecord order,
            String employeeDisplayLabel,
            int onlineCapacity,
            int minimumPreparationLeadSeconds,
            int serviceSecondsPerMeal) { }
}
