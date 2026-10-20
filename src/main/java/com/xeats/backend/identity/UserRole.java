package com.xeats.backend.identity;

public enum UserRole {
    EMPLOYEE,
    VENDOR_STAFF,
    DEMO_ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }
}
