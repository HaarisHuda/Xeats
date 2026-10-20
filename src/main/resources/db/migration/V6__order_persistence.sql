-- Orders retain the counter/item/slot values shown when the employee confirmed the meal.
-- The composite keys also prevent a snapshot from referencing an item or slot at another counter.
ALTER TABLE menu_item
    ADD CONSTRAINT uq_menu_item_id_counter UNIQUE (id, counter_id);

ALTER TABLE pickup_slot
    ADD CONSTRAINT uq_pickup_slot_id_counter UNIQUE (id, counter_id);

CREATE TABLE meal_order (
    id BINARY(16) NOT NULL,
    employee_id BINARY(16) NOT NULL,
    counter_id BINARY(16) NOT NULL,
    menu_item_id BINARY(16) NOT NULL,
    pickup_slot_id BINARY(16) NOT NULL,
    item_name_snapshot VARCHAR(120) NOT NULL,
    item_price_minor_snapshot BIGINT NOT NULL,
    item_currency_snapshot CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    counter_name_snapshot VARCHAR(80) NOT NULL,
    pickup_instructions_snapshot VARCHAR(300) NOT NULL,
    pickup_window_start_at TIMESTAMP(6) NOT NULL,
    pickup_window_end_at TIMESTAMP(6) NOT NULL,
    status ENUM('ACCEPTED', 'PREPARING', 'READY', 'COLLECTED', 'EXPIRED') NOT NULL,
    version INT UNSIGNED NOT NULL,
    simulated_payment_status ENUM('SIMULATED_SUCCEEDED') NOT NULL,
    placed_at TIMESTAMP(6) NOT NULL,
    accepted_at TIMESTAMP(6) NOT NULL,
    preparing_at TIMESTAMP(6) NULL,
    ready_at TIMESTAMP(6) NULL,
    collected_at TIMESTAMP(6) NULL,
    expired_at TIMESTAMP(6) NULL,
    expired_reason VARCHAR(500) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_meal_order_id_employee_counter (id, employee_id, counter_id),
    KEY ix_meal_order_employee_placed (employee_id, placed_at, id),
    KEY ix_meal_order_counter_status_pickup (counter_id, status, pickup_window_start_at, placed_at, id),
    CONSTRAINT chk_meal_order_price CHECK (item_price_minor_snapshot >= 0),
    CONSTRAINT chk_meal_order_currency CHECK (item_currency_snapshot = 'INR'),
    CONSTRAINT chk_meal_order_version CHECK (version >= 1),
    CONSTRAINT chk_meal_order_pickup_window CHECK (pickup_window_end_at > pickup_window_start_at),
    CONSTRAINT chk_meal_order_accepted_time CHECK (accepted_at >= placed_at),
    CONSTRAINT chk_meal_order_preparing_time CHECK (
        status NOT IN ('PREPARING', 'READY', 'COLLECTED') OR preparing_at IS NOT NULL),
    CONSTRAINT chk_meal_order_ready_time CHECK (
        status NOT IN ('READY', 'COLLECTED') OR ready_at IS NOT NULL),
    CONSTRAINT chk_meal_order_collected_time CHECK (status <> 'COLLECTED' OR collected_at IS NOT NULL),
    CONSTRAINT chk_meal_order_expired_fields CHECK (
        (status = 'EXPIRED' AND expired_at IS NOT NULL AND expired_reason IS NOT NULL
            AND CHAR_LENGTH(TRIM(expired_reason)) > 0)
        OR (status <> 'EXPIRED' AND expired_at IS NULL AND expired_reason IS NULL)),
    CONSTRAINT fk_meal_order_employee FOREIGN KEY (employee_id)
        REFERENCES app_user (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_meal_order_counter FOREIGN KEY (counter_id)
        REFERENCES counter (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_meal_order_item_counter FOREIGN KEY (menu_item_id, counter_id)
        REFERENCES menu_item (id, counter_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_meal_order_slot_counter FOREIGN KEY (pickup_slot_id, counter_id)
        REFERENCES pickup_slot (id, counter_id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE active_order_claim (
    employee_id BINARY(16) NOT NULL,
    counter_id BINARY(16) NOT NULL,
    order_id BINARY(16) NOT NULL,
    claimed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (employee_id, counter_id),
    UNIQUE KEY uq_active_order_claim_order (order_id),
    CONSTRAINT fk_active_order_claim_order_owner_counter
        FOREIGN KEY (order_id, employee_id, counter_id)
        REFERENCES meal_order (id, employee_id, counter_id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE pickup_token (
    order_id BINARY(16) NOT NULL,
    code VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    issued_at TIMESTAMP(6) NOT NULL,
    redeemed_at TIMESTAMP(6) NULL,
    invalidated_at TIMESTAMP(6) NULL,
    PRIMARY KEY (order_id),
    UNIQUE KEY uq_pickup_token_code (code),
    CONSTRAINT chk_pickup_token_code CHECK (REGEXP_LIKE(code, '^[0-9A-HJKMNP-TV-Z]{10}$')),
    CONSTRAINT chk_pickup_token_terminal_time CHECK (redeemed_at IS NULL OR invalidated_at IS NULL),
    CONSTRAINT fk_pickup_token_order FOREIGN KEY (order_id)
        REFERENCES meal_order (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE order_status_event (
    order_id BINARY(16) NOT NULL,
    order_version INT UNSIGNED NOT NULL,
    previous_status ENUM('ACCEPTED', 'PREPARING', 'READY', 'COLLECTED', 'EXPIRED') NULL,
    new_status ENUM('ACCEPTED', 'PREPARING', 'READY', 'COLLECTED', 'EXPIRED') NOT NULL,
    actor_user_id BINARY(16) NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    expired_reason VARCHAR(500) NULL,
    PRIMARY KEY (order_id, order_version),
    KEY ix_order_status_event_actor (actor_user_id, occurred_at),
    CONSTRAINT chk_order_status_event_version CHECK (order_version >= 1),
    CONSTRAINT chk_order_status_event_initial CHECK (
        (order_version = 1 AND previous_status IS NULL AND new_status = 'ACCEPTED')
        OR (order_version > 1 AND previous_status IS NOT NULL)),
    CONSTRAINT chk_order_status_event_expiry CHECK (
        (new_status = 'EXPIRED' AND expired_reason IS NOT NULL
            AND CHAR_LENGTH(TRIM(expired_reason)) > 0)
        OR (new_status <> 'EXPIRED' AND expired_reason IS NULL)),
    CONSTRAINT fk_order_status_event_order FOREIGN KEY (order_id)
        REFERENCES meal_order (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_order_status_event_actor FOREIGN KEY (actor_user_id)
        REFERENCES app_user (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE idempotency_record (
    employee_id BINARY(16) NOT NULL,
    operation ENUM('CREATE_ORDER') NOT NULL,
    idempotency_key BINARY(16) NOT NULL,
    request_fingerprint BINARY(32) NOT NULL,
    order_id BINARY(16) NOT NULL,
    first_status SMALLINT UNSIGNED NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (employee_id, operation, idempotency_key),
    UNIQUE KEY uq_idempotency_record_order (order_id),
    CONSTRAINT chk_idempotency_record_first_status CHECK (first_status = 201),
    CONSTRAINT fk_idempotency_record_employee FOREIGN KEY (employee_id)
        REFERENCES app_user (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_idempotency_record_order FOREIGN KEY (order_id)
        REFERENCES meal_order (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;
