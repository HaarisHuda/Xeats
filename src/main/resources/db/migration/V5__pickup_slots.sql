-- Counters created by repeatable demo/test fixtures predate policy creation in the admin API.
INSERT INTO counter_capacity_policy (
    counter_id, revision, effective_at, slot_duration_minutes, total_meals_per_slot,
    walk_in_reserve_percent, minimum_preparation_lead_seconds, maximum_advance_minutes,
    service_seconds_per_meal)
SELECT c.id, 1, UTC_TIMESTAMP(6), 5, 6, 30, 120, 60, 75
FROM counter c
WHERE NOT EXISTS (
    SELECT 1 FROM counter_capacity_policy p WHERE p.counter_id = c.id
);

CREATE TABLE pickup_slot (
    id BINARY(16) NOT NULL,
    counter_id BINARY(16) NOT NULL,
    policy_revision INT UNSIGNED NOT NULL,
    start_at TIMESTAMP(6) NOT NULL,
    end_at TIMESTAMP(6) NOT NULL,
    total_capacity_snapshot SMALLINT UNSIGNED NOT NULL,
    online_capacity_snapshot SMALLINT UNSIGNED NOT NULL,
    confirmed_allocation_count SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_pickup_slot_counter_start UNIQUE (counter_id, start_at),
    KEY ix_pickup_slot_counter_end (counter_id, end_at),
    CONSTRAINT chk_pickup_slot_window CHECK (end_at > start_at),
    CONSTRAINT chk_pickup_slot_total_capacity CHECK (total_capacity_snapshot BETWEEN 1 AND 200),
    CONSTRAINT chk_pickup_slot_online_capacity CHECK (online_capacity_snapshot <= total_capacity_snapshot),
    CONSTRAINT chk_pickup_slot_allocations CHECK (confirmed_allocation_count <= online_capacity_snapshot),
    CONSTRAINT fk_pickup_slot_counter FOREIGN KEY (counter_id)
        REFERENCES counter (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_pickup_slot_policy FOREIGN KEY (counter_id, policy_revision)
        REFERENCES counter_capacity_policy (counter_id, revision) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;
