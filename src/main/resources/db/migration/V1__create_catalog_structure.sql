CREATE TABLE food_court (
    id BINARY(16) NOT NULL,
    code VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(120) NOT NULL,
    time_zone VARCHAR(64) NOT NULL DEFAULT 'Asia/Kolkata',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_food_court_code UNIQUE (code)
) ENGINE=InnoDB;

CREATE TABLE vendor (
    id BINARY(16) NOT NULL,
    food_court_id BINARY(16) NOT NULL,
    name VARCHAR(120) NOT NULL,
    service_model VARCHAR(40) NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_vendor_food_court (food_court_id),
    CONSTRAINT fk_vendor_food_court FOREIGN KEY (food_court_id)
        REFERENCES food_court (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE counter (
    id BINARY(16) NOT NULL,
    vendor_id BINARY(16) NOT NULL,
    name VARCHAR(80) NOT NULL,
    pickup_instructions VARCHAR(300) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    accepting_online_orders BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY ix_counter_vendor (vendor_id),
    CONSTRAINT fk_counter_vendor FOREIGN KEY (vendor_id)
        REFERENCES vendor (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE menu_item (
    id BINARY(16) NOT NULL,
    counter_id BINARY(16) NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500) NULL,
    category VARCHAR(60) NULL,
    price_minor BIGINT NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'INR',
    availability ENUM('AVAILABLE', 'SOLD_OUT') NOT NULL DEFAULT 'AVAILABLE',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_menu_item_counter UNIQUE (counter_id),
    CONSTRAINT chk_menu_item_price_nonnegative CHECK (price_minor >= 0),
    CONSTRAINT chk_menu_item_currency_inr CHECK (currency = 'INR'),
    CONSTRAINT fk_menu_item_counter FOREIGN KEY (counter_id)
        REFERENCES counter (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;
