CREATE TABLE counter_capacity_policy (
    counter_id BINARY(16) NOT NULL,
    revision INT UNSIGNED NOT NULL,
    effective_at TIMESTAMP(6) NOT NULL,
    slot_duration_minutes TINYINT UNSIGNED NOT NULL,
    total_meals_per_slot SMALLINT UNSIGNED NOT NULL,
    walk_in_reserve_percent TINYINT UNSIGNED NOT NULL,
    minimum_preparation_lead_seconds SMALLINT UNSIGNED NOT NULL,
    maximum_advance_minutes SMALLINT UNSIGNED NOT NULL,
    service_seconds_per_meal SMALLINT UNSIGNED NOT NULL,
    PRIMARY KEY (counter_id, revision),
    CONSTRAINT chk_capacity_policy_revision CHECK (revision >= 1),
    CONSTRAINT chk_capacity_policy_slot_duration CHECK (slot_duration_minutes IN (5, 10, 15, 20, 25, 30)),
    CONSTRAINT chk_capacity_policy_total_meals CHECK (total_meals_per_slot BETWEEN 1 AND 200),
    CONSTRAINT chk_capacity_policy_walk_in CHECK (walk_in_reserve_percent BETWEEN 0 AND 100),
    CONSTRAINT chk_capacity_policy_prep_lead CHECK (minimum_preparation_lead_seconds BETWEEN 0 AND 1800),
    CONSTRAINT chk_capacity_policy_advance CHECK (maximum_advance_minutes BETWEEN 15 AND 1440),
    CONSTRAINT chk_capacity_policy_service CHECK (service_seconds_per_meal BETWEEN 15 AND 600),
    CONSTRAINT fk_capacity_policy_counter FOREIGN KEY (counter_id)
        REFERENCES counter (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE counter_change_event (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    counter_id BINARY(16) NOT NULL,
    actor_user_id BINARY(16) NOT NULL,
    field_name VARCHAR(60) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    previous_value JSON NULL,
    new_value JSON NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    KEY ix_counter_change_event_counter (counter_id, occurred_at, id),
    KEY ix_counter_change_event_actor (actor_user_id, occurred_at),
    CONSTRAINT fk_counter_change_event_counter FOREIGN KEY (counter_id)
        REFERENCES counter (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_counter_change_event_actor FOREIGN KEY (actor_user_id)
        REFERENCES app_user (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;
