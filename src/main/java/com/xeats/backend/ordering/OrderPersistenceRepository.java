package com.xeats.backend.ordering;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.xeats.backend.ordering.OrderPersistenceModels.ActiveOrderClaim;
import com.xeats.backend.ordering.OrderPersistenceModels.IdempotencyRecord;
import com.xeats.backend.ordering.OrderPersistenceModels.OrderRecord;
import com.xeats.backend.ordering.OrderPersistenceModels.ReadinessOrder;
import com.xeats.backend.ordering.OrderPersistenceModels.OrderStatusEvent;
import com.xeats.backend.ordering.OrderPersistenceModels.OrderWithToken;
import com.xeats.backend.ordering.OrderPersistenceModels.PickupToken;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class OrderPersistenceRepository {
    static final RowMapper<OrderRecord> ORDER_MAPPER = (result, row) -> new OrderRecord(
            UUID.fromString(result.getString("id")),
            UUID.fromString(result.getString("employee_id")),
            UUID.fromString(result.getString("counter_id")),
            UUID.fromString(result.getString("menu_item_id")),
            UUID.fromString(result.getString("pickup_slot_id")),
            result.getString("item_name_snapshot"),
            result.getLong("item_price_minor_snapshot"),
            result.getString("item_currency_snapshot"),
            result.getString("counter_name_snapshot"),
            result.getString("pickup_instructions_snapshot"),
            instant(result.getTimestamp("pickup_window_start_at")),
            instant(result.getTimestamp("pickup_window_end_at")),
            OrderStatus.valueOf(result.getString("status")),
            result.getLong("version"),
            result.getString("simulated_payment_status"),
            instant(result.getTimestamp("placed_at")),
            instant(result.getTimestamp("accepted_at")),
            instant(result.getTimestamp("preparing_at")),
            instant(result.getTimestamp("ready_at")),
            instant(result.getTimestamp("collected_at")),
            instant(result.getTimestamp("expired_at")),
            result.getString("expired_reason"));

    private final JdbcTemplate jdbc;

    public OrderPersistenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Unlocked identity lookup used only to determine the shared counter lock to acquire first. */
    public Optional<OrderIdentity> findIdentity(UUID orderId) {
        List<OrderIdentity> matches = jdbc.query("""
                SELECT BIN_TO_UUID(employee_id) AS employee_id, BIN_TO_UUID(counter_id) AS counter_id
                FROM meal_order WHERE id = UUID_TO_BIN(?)
                """, (result, row) -> new OrderIdentity(
                UUID.fromString(result.getString("employee_id")),
                UUID.fromString(result.getString("counter_id"))), orderId.toString());
        return matches.stream().findFirst();
    }

    public Optional<OrderRecord> findById(UUID orderId) {
        return findOrder(orderId, false);
    }

    public Optional<OrderRecord> findByIdForUpdate(UUID orderId) {
        return findOrder(orderId, true);
    }

    public Optional<OrderWithToken> findByIdWithToken(UUID orderId) {
        List<OrderWithToken> matches = jdbc.query(orderSelect(true) + """
                WHERE o.id = UUID_TO_BIN(?)
                """, (result, row) -> new OrderWithToken(
                ORDER_MAPPER.mapRow(result, row),
                new PickupToken(
                        UUID.fromString(result.getString("id")), result.getString("pickup_code"),
                        instant(result.getTimestamp("token_issued_at")),
                        instant(result.getTimestamp("token_redeemed_at")),
                        instant(result.getTimestamp("token_invalidated_at")))), orderId.toString());
        return matches.stream().findFirst();
    }

    /** Reads owner-filtered detail and token in one query so a non-owner cannot probe token storage. */
    public Optional<OrderWithToken> findOwnedByIdWithToken(UUID orderId, UUID employeeId) {
        List<OrderWithToken> matches = jdbc.query(orderSelect(true) + """
                WHERE o.id = UUID_TO_BIN(?) AND o.employee_id = UUID_TO_BIN(?)
                """, (result, row) -> new OrderWithToken(
                ORDER_MAPPER.mapRow(result, row),
                new PickupToken(
                        UUID.fromString(result.getString("id")), result.getString("pickup_code"),
                        instant(result.getTimestamp("token_issued_at")),
                        instant(result.getTimestamp("token_redeemed_at")),
                        instant(result.getTimestamp("token_invalidated_at")))),
                orderId.toString(), employeeId.toString());
        return matches.stream().findFirst();
    }

    /** Stable newest-first paging with an id tie-breaker and optional lifecycle-state filtering. */
    public List<OrderRecord> findOrdersForEmployee(
            UUID employeeId, List<OrderStatus> statuses, int size, long offset) {
        List<Object> parameters = new java.util.ArrayList<>();
        parameters.add(employeeId.toString());
        String statusClause = "";
        if (statuses != null && !statuses.isEmpty()) {
            statusClause = " AND o.status IN ("
                    + statuses.stream().map(ignored -> "?").collect(Collectors.joining(",")) + ")";
            parameters.addAll(statuses.stream().map(Enum::name).toList());
        }
        parameters.add(size);
        parameters.add(offset);
        return jdbc.query(orderSelect() + " WHERE o.employee_id = UUID_TO_BIN(?)" + statusClause
                        + " ORDER BY o.placed_at DESC, o.id DESC LIMIT ? OFFSET ?",
                ORDER_MAPPER, parameters.toArray());
    }

    public long countOrdersForEmployee(UUID employeeId, List<OrderStatus> statuses) {
        List<Object> parameters = new java.util.ArrayList<>();
        parameters.add(employeeId.toString());
        String statusClause = "";
        if (statuses != null && !statuses.isEmpty()) {
            statusClause = " AND status IN ("
                    + statuses.stream().map(ignored -> "?").collect(Collectors.joining(",")) + ")";
            parameters.addAll(statuses.stream().map(Enum::name).toList());
        }
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM meal_order WHERE employee_id = UUID_TO_BIN(?)"
                + statusClause, Long.class, parameters.toArray());
        return count == null ? 0 : count;
    }

    /** Loads counter workload without joining employee identities or pickup-token data. */
    public List<ReadinessOrder> findReadinessOrdersForCounter(UUID counterId) {
        return findReadinessOrdersForCounters(Set.of(counterId));
    }

    /** Batch form for employee order pages so ETA projection does not issue one query per order. */
    public List<ReadinessOrder> findReadinessOrdersForCounters(Set<UUID> counterIds) {
        if (counterIds.isEmpty()) {
            return List.of();
        }
        String placeholders = counterIds.stream().map(ignored -> "UUID_TO_BIN(?)")
                .collect(Collectors.joining(", "));
        Object[] parameters = counterIds.stream().map(UUID::toString).toArray();
        return jdbc.query(readinessOrderSelect() + " WHERE o.counter_id IN (" + placeholders + ")",
                (result, row) -> new ReadinessOrder(
                        ORDER_MAPPER.mapRow(result, row),
                        result.getInt("online_capacity_snapshot"),
                        result.getInt("minimum_preparation_lead_seconds"),
                        result.getInt("service_seconds_per_meal")), parameters);
    }

    public void insertOrder(OrderRecord order) {
        jdbc.update("""
                INSERT INTO meal_order (
                    id, employee_id, counter_id, menu_item_id, pickup_slot_id,
                    item_name_snapshot, item_price_minor_snapshot, item_currency_snapshot,
                    counter_name_snapshot, pickup_instructions_snapshot,
                    pickup_window_start_at, pickup_window_end_at, status, version,
                    simulated_payment_status, placed_at, accepted_at, preparing_at, ready_at,
                    collected_at, expired_at, expired_reason)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?),
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, order.id().toString(), order.employeeId().toString(), order.counterId().toString(),
                order.menuItemId().toString(), order.pickupSlotId().toString(), order.itemName(),
                order.itemPriceMinor(), order.itemCurrency(), order.counterName(), order.pickupInstructions(),
                Timestamp.from(order.pickupWindowStartAt()), Timestamp.from(order.pickupWindowEndAt()),
                order.status().name(), order.version(), order.simulatedPaymentStatus(), Timestamp.from(order.placedAt()),
                Timestamp.from(order.acceptedAt()), timestamp(order.preparingAt()), timestamp(order.readyAt()),
                timestamp(order.collectedAt()), timestamp(order.expiredAt()), order.expiredReason());
    }

    public void issuePickupToken(UUID orderId, String code, Instant issuedAt) {
        jdbc.update("""
                INSERT INTO pickup_token (order_id, code, issued_at)
                VALUES (UUID_TO_BIN(?), ?, ?)
                """, orderId.toString(), code, Timestamp.from(issuedAt));
    }

    /** Call while holding the shared counter lock and after the order row has been inserted. */
    public void createActiveOrderClaim(UUID employeeId, UUID counterId, UUID orderId, Instant claimedAt) {
        jdbc.update("""
                INSERT INTO active_order_claim (employee_id, counter_id, order_id, claimed_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?)
                """, employeeId.toString(), counterId.toString(), orderId.toString(), Timestamp.from(claimedAt));
    }

    public boolean hasActiveOrderClaim(UUID employeeId, UUID counterId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM active_order_claim
                    WHERE employee_id = UUID_TO_BIN(?) AND counter_id = UUID_TO_BIN(?)
                )
                """, Boolean.class, employeeId.toString(), counterId.toString()));
    }

    public Optional<IdempotencyRecord> findIdempotencyRecord(UUID employeeId, UUID key) {
        return findIdempotencyRecord(employeeId, key, false);
    }

    public Optional<IdempotencyRecord> findIdempotencyRecordForUpdate(UUID employeeId, UUID key) {
        return findIdempotencyRecord(employeeId, key, true);
    }

    public void insertIdempotencyRecord(IdempotencyRecord record) {
        if (record.requestFingerprintSha256() == null
                || !record.requestFingerprintSha256().matches("(?i)^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("An idempotency request fingerprint must be a SHA-256 hex value.");
        }
        jdbc.update("""
                INSERT INTO idempotency_record (
                    employee_id, operation, idempotency_key, request_fingerprint, order_id, first_status, created_at)
                VALUES (UUID_TO_BIN(?), ?, UUID_TO_BIN(?), UNHEX(?), UUID_TO_BIN(?), ?, ?)
                """, record.employeeId().toString(), record.operation().name(), record.key().toString(),
                record.requestFingerprintSha256(), record.orderId().toString(), record.firstHttpStatus(),
                Timestamp.from(record.createdAt()));
    }

    public Optional<PickupToken> lockPickupToken(UUID orderId) {
        return findPickupToken(orderId, true);
    }

    public Optional<PickupToken> findPickupToken(UUID orderId) {
        return findPickupToken(orderId, false);
    }

    /** Resolves an immutable code to its order and counter before the transaction acquires domain locks. */
    public Optional<PickupRedemptionIdentity> findPickupRedemptionIdentity(String code) {
        List<PickupRedemptionIdentity> matches = jdbc.query("""
                SELECT BIN_TO_UUID(o.id) AS order_id, BIN_TO_UUID(o.counter_id) AS counter_id
                FROM pickup_token t
                JOIN meal_order o ON o.id = t.order_id
                WHERE t.code = ?
                """, (result, row) -> new PickupRedemptionIdentity(
                UUID.fromString(result.getString("order_id")),
                UUID.fromString(result.getString("counter_id"))), code);
        return matches.stream().findFirst();
    }

    private Optional<PickupToken> findPickupToken(UUID orderId, boolean forUpdate) {
        List<PickupToken> matches = jdbc.query("""
                SELECT BIN_TO_UUID(order_id) AS order_id, code, issued_at, redeemed_at, invalidated_at
                FROM pickup_token WHERE order_id = UUID_TO_BIN(?)
                """ + (forUpdate ? " FOR UPDATE" : ""), (result, row) -> new PickupToken(
                UUID.fromString(result.getString("order_id")), result.getString("code"),
                instant(result.getTimestamp("issued_at")), instant(result.getTimestamp("redeemed_at")),
                instant(result.getTimestamp("invalidated_at"))), orderId.toString());
        return matches.stream().findFirst();
    }

    public Optional<ActiveOrderClaim> lockActiveClaimByOrder(UUID orderId) {
        List<ActiveOrderClaim> matches = jdbc.query("""
                SELECT BIN_TO_UUID(employee_id) AS employee_id, BIN_TO_UUID(counter_id) AS counter_id,
                    BIN_TO_UUID(order_id) AS order_id, claimed_at
                FROM active_order_claim WHERE order_id = UUID_TO_BIN(?) FOR UPDATE
                """, (result, row) -> new ActiveOrderClaim(
                UUID.fromString(result.getString("employee_id")),
                UUID.fromString(result.getString("counter_id")),
                UUID.fromString(result.getString("order_id")),
                instant(result.getTimestamp("claimed_at"))), orderId.toString());
        return matches.stream().findFirst();
    }

    public int markTokenTerminal(UUID orderId, OrderStatus status, Instant at) {
        if (status == OrderStatus.COLLECTED) {
            return jdbc.update("""
                    UPDATE pickup_token SET redeemed_at = ?
                    WHERE order_id = UUID_TO_BIN(?) AND redeemed_at IS NULL AND invalidated_at IS NULL
                    """, Timestamp.from(at), orderId.toString());
        }
        if (status == OrderStatus.EXPIRED) {
            return jdbc.update("""
                    UPDATE pickup_token SET invalidated_at = ?
                    WHERE order_id = UUID_TO_BIN(?) AND redeemed_at IS NULL AND invalidated_at IS NULL
                    """, Timestamp.from(at), orderId.toString());
        }
        throw new IllegalArgumentException("Only a terminal status can close a pickup token.");
    }

    public int updateStatus(
            UUID orderId, long expectedVersion, OrderStatus status, Instant at, String expiredReason) {
        String timestampColumn = switch (status) {
            case PREPARING -> "preparing_at";
            case READY -> "ready_at";
            case COLLECTED -> "collected_at";
            case EXPIRED -> "expired_at";
            case ACCEPTED -> throw new IllegalArgumentException("An order cannot transition back to ACCEPTED.");
        };
        return jdbc.update("""
                UPDATE meal_order
                SET status = ?, version = version + 1, %s = ?, expired_reason = ?
                WHERE id = UUID_TO_BIN(?) AND version = ?
                """.formatted(timestampColumn), status.name(), Timestamp.from(at), expiredReason,
                orderId.toString(), expectedVersion);
    }

    public int releaseActiveClaim(UUID orderId, UUID employeeId, UUID counterId) {
        return jdbc.update("""
                DELETE FROM active_order_claim
                WHERE order_id = UUID_TO_BIN(?) AND employee_id = UUID_TO_BIN(?) AND counter_id = UUID_TO_BIN(?)
                """, orderId.toString(), employeeId.toString(), counterId.toString());
    }

    public void appendStatusEvent(
            UUID orderId,
            long version,
            OrderStatus previousStatus,
            OrderStatus newStatus,
            UUID actorUserId,
            Instant occurredAt,
            String expiredReason) {
        jdbc.update("""
                INSERT INTO order_status_event (
                    order_id, order_version, previous_status, new_status, actor_user_id, occurred_at, expired_reason)
                VALUES (UUID_TO_BIN(?), ?, ?, ?, UUID_TO_BIN(?), ?, ?)
                """, orderId.toString(), version,
                previousStatus == null ? null : previousStatus.name(), newStatus.name(), actorUserId.toString(),
                Timestamp.from(occurredAt), expiredReason);
    }

    public List<OrderStatusEvent> findStatusEvents(UUID orderId) {
        return jdbc.query("""
                SELECT BIN_TO_UUID(order_id) AS order_id, order_version, previous_status, new_status,
                    BIN_TO_UUID(actor_user_id) AS actor_user_id, occurred_at, expired_reason
                FROM order_status_event WHERE order_id = UUID_TO_BIN(?) ORDER BY order_version
                """, (result, row) -> new OrderStatusEvent(
                UUID.fromString(result.getString("order_id")), result.getLong("order_version"),
                nullableStatus(result.getString("previous_status")), OrderStatus.valueOf(result.getString("new_status")),
                UUID.fromString(result.getString("actor_user_id")), instant(result.getTimestamp("occurred_at")),
                result.getString("expired_reason")), orderId.toString());
    }

    private Optional<OrderRecord> findOrder(UUID orderId, boolean forUpdate) {
        List<OrderRecord> matches = jdbc.query(orderSelect() + " WHERE o.id = UUID_TO_BIN(?)"
                + (forUpdate ? " FOR UPDATE" : ""), ORDER_MAPPER, orderId.toString());
        return matches.stream().findFirst();
    }

    private Optional<IdempotencyRecord> findIdempotencyRecord(UUID employeeId, UUID key, boolean forUpdate) {
        List<IdempotencyRecord> matches = jdbc.query("""
                SELECT BIN_TO_UUID(employee_id) AS employee_id, operation, BIN_TO_UUID(idempotency_key) AS key_id,
                    LOWER(HEX(request_fingerprint)) AS request_fingerprint, BIN_TO_UUID(order_id) AS order_id,
                    first_status, created_at
                FROM idempotency_record
                WHERE employee_id = UUID_TO_BIN(?) AND operation = 'CREATE_ORDER'
                    AND idempotency_key = UUID_TO_BIN(?)
                """ + (forUpdate ? " FOR UPDATE" : ""), (result, row) -> new IdempotencyRecord(
                UUID.fromString(result.getString("employee_id")),
                OrderIdempotencyOperation.valueOf(result.getString("operation")),
                UUID.fromString(result.getString("key_id")), result.getString("request_fingerprint"),
                UUID.fromString(result.getString("order_id")), result.getInt("first_status"),
                instant(result.getTimestamp("created_at"))), employeeId.toString(), key.toString());
        return matches.stream().findFirst();
    }

    String orderSelect() {
        return orderSelect(false);
    }

    /** Order projection with the immutable slot and policy context needed by the staff queue. */
    String staffOrderSelect() {
        return orderSelect(false,
                ", u.username AS employee_display_label, s.online_capacity_snapshot,"
                        + " p.minimum_preparation_lead_seconds, p.service_seconds_per_meal",
                " JOIN app_user u ON u.id = o.employee_id"
                        + " JOIN pickup_slot s ON s.id = o.pickup_slot_id AND s.counter_id = o.counter_id"
                        + " JOIN counter_capacity_policy p"
                        + " ON p.counter_id = s.counter_id AND p.revision = s.policy_revision");
    }

    private String readinessOrderSelect() {
        return orderSelect(false,
                ", s.online_capacity_snapshot, p.minimum_preparation_lead_seconds, p.service_seconds_per_meal",
                " JOIN pickup_slot s ON s.id = o.pickup_slot_id AND s.counter_id = o.counter_id"
                        + " JOIN counter_capacity_policy p"
                        + " ON p.counter_id = s.counter_id AND p.revision = s.policy_revision");
    }

    private String orderSelect(boolean includeToken) {
        return orderSelect(includeToken, "", "");
    }

    private String orderSelect(boolean includeToken, String additionalColumns, String additionalJoins) {
        String tokenColumns = includeToken
                ? ", t.code AS pickup_code, t.issued_at AS token_issued_at,"
                        + " t.redeemed_at AS token_redeemed_at, t.invalidated_at AS token_invalidated_at"
                : "";
        String tokenJoin = includeToken ? " JOIN pickup_token t ON t.order_id = o.id " : " ";
        return """
                SELECT BIN_TO_UUID(o.id) AS id, BIN_TO_UUID(o.employee_id) AS employee_id,
                    BIN_TO_UUID(o.counter_id) AS counter_id, BIN_TO_UUID(o.menu_item_id) AS menu_item_id,
                    BIN_TO_UUID(o.pickup_slot_id) AS pickup_slot_id, o.item_name_snapshot,
                    o.item_price_minor_snapshot, o.item_currency_snapshot, o.counter_name_snapshot,
                    o.pickup_instructions_snapshot, o.pickup_window_start_at, o.pickup_window_end_at,
                    o.status, o.version, o.simulated_payment_status, o.placed_at, o.accepted_at,
                    o.preparing_at, o.ready_at, o.collected_at, o.expired_at, o.expired_reason%s%s
                FROM meal_order o%s%s
                """.formatted(additionalColumns, tokenColumns, tokenJoin, additionalJoins);
    }

    private static OrderStatus nullableStatus(String value) {
        return value == null ? null : OrderStatus.valueOf(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    public record OrderIdentity(UUID employeeId, UUID counterId) {
    }

    public record PickupRedemptionIdentity(UUID orderId, UUID counterId) {
    }
}
