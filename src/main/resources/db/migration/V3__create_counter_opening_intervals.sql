CREATE TABLE counter_opening_interval (
    counter_id BINARY(16) NOT NULL,
    iso_weekday TINYINT UNSIGNED NOT NULL,
    opens_at TIME NOT NULL,
    closes_at TIME NOT NULL,
    PRIMARY KEY (counter_id, iso_weekday, opens_at),
    CONSTRAINT chk_opening_interval_weekday CHECK (iso_weekday BETWEEN 1 AND 7),
    CONSTRAINT chk_opening_interval_nonzero CHECK (opens_at <> closes_at),
    CONSTRAINT fk_opening_interval_counter FOREIGN KEY (counter_id)
        REFERENCES counter (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;
