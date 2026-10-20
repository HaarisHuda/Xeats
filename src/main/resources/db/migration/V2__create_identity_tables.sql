CREATE TABLE app_user (
    id BINARY(16) NOT NULL,
    username VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    password_hash VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_app_user_username UNIQUE (username)
) ENGINE=InnoDB;

CREATE TABLE user_role (
    user_id BINARY(16) NOT NULL,
    role ENUM('EMPLOYEE', 'VENDOR_STAFF', 'DEMO_ADMIN') NOT NULL,
    granted_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, role),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id)
        REFERENCES app_user (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE staff_counter_assignment (
    user_id BINARY(16) NOT NULL,
    counter_id BINARY(16) NOT NULL,
    assigned_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, counter_id),
    KEY ix_staff_counter_assignment_counter (counter_id),
    CONSTRAINT fk_staff_counter_assignment_user FOREIGN KEY (user_id)
        REFERENCES app_user (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_staff_counter_assignment_counter FOREIGN KEY (counter_id)
        REFERENCES counter (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;
