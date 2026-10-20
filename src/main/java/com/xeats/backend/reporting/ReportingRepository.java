package com.xeats.backend.reporting;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.xeats.backend.ordering.OrderStatus;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReportingRepository {
    private final JdbcTemplate jdbc;

    public ReportingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> findCounterTimeZone(UUID counterId) {
        List<String> timeZones = jdbc.query("""
                SELECT f.time_zone
                FROM counter c
                JOIN vendor v ON v.id = c.vendor_id
                JOIN food_court f ON f.id = v.food_court_id
                WHERE c.id = UUID_TO_BIN(?)
                """, (result, row) -> result.getString("time_zone"), counterId.toString());
        return timeZones.stream().findFirst();
    }

    /** Returns only historical snapshots/timestamps needed by the report, never employee or token data. */
    public List<ReportOrder> findReportOrders(UUID counterId, Instant fromInclusive, Instant toExclusive) {
        Timestamp from = Timestamp.from(fromInclusive);
        Timestamp to = Timestamp.from(toExclusive);
        return jdbc.query("""
                SELECT o.item_name_snapshot, o.item_price_minor_snapshot, o.item_currency_snapshot,
                    o.status, o.placed_at, o.accepted_at, o.preparing_at, o.ready_at, o.collected_at,
                    o.expired_at, o.pickup_window_end_at
                FROM meal_order o
                WHERE o.counter_id = UUID_TO_BIN(?)
                    AND ((o.placed_at >= ? AND o.placed_at < ?)
                        OR (o.accepted_at >= ? AND o.accepted_at < ?)
                        OR (o.collected_at >= ? AND o.collected_at < ?)
                        OR (o.expired_at >= ? AND o.expired_at < ?))
                """, (result, row) -> new ReportOrder(
                result.getString("item_name_snapshot"),
                result.getLong("item_price_minor_snapshot"),
                result.getString("item_currency_snapshot"),
                OrderStatus.valueOf(result.getString("status")),
                instant(result.getTimestamp("placed_at")),
                instant(result.getTimestamp("accepted_at")),
                instant(result.getTimestamp("preparing_at")),
                instant(result.getTimestamp("ready_at")),
                instant(result.getTimestamp("collected_at")),
                instant(result.getTimestamp("expired_at")),
                instant(result.getTimestamp("pickup_window_end_at"))),
                counterId.toString(), from, to, from, to, from, to, from, to);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record ReportOrder(
            String itemName,
            long itemPriceMinor,
            String currency,
            OrderStatus status,
            Instant placedAt,
            Instant acceptedAt,
            Instant preparingAt,
            Instant readyAt,
            Instant collectedAt,
            Instant expiredAt,
            Instant pickupWindowEndAt) { }
}
