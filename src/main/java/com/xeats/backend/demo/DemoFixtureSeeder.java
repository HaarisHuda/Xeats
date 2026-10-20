package com.xeats.backend.demo;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import com.xeats.backend.catalog.CatalogRepository;
import com.xeats.backend.catalog.Counter;
import com.xeats.backend.catalog.CounterOpeningInterval;
import com.xeats.backend.catalog.FoodCourt;
import com.xeats.backend.catalog.MenuItem;
import com.xeats.backend.catalog.MenuItemAvailability;
import com.xeats.backend.catalog.Vendor;
import com.xeats.backend.catalog.VendorServiceModel;
import com.xeats.backend.identity.UserRole;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Repeatable synthetic fixtures shared by local demo runs and later API integration tests. */
@Service
public class DemoFixtureSeeder {
    private static final LocalTime OPENS_AT = LocalTime.of(11, 0);
    private static final LocalTime CLOSES_AT = LocalTime.of(14, 0);
    private static final List<DayOfWeek> OPEN_DAYS = List.of(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY);
    private static final List<DemoAccount> DEMO_ACCOUNTS = List.of(
            new DemoAccount(401, "employee.demo.01", UserRole.EMPLOYEE),
            new DemoAccount(402, "employee.demo.02", UserRole.EMPLOYEE),
            new DemoAccount(403, "employee.demo.03", UserRole.EMPLOYEE),
            new DemoAccount(411, "staff.north.thali", UserRole.VENDOR_STAFF),
            new DemoAccount(412, "staff.north.wraps", UserRole.VENDOR_STAFF),
            new DemoAccount(413, "staff.south.rice", UserRole.VENDOR_STAFF),
            new DemoAccount(414, "staff.independent.01", UserRole.VENDOR_STAFF),
            new DemoAccount(415, "staff.independent.02", UserRole.VENDOR_STAFF),
            new DemoAccount(416, "staff.independent.03", UserRole.VENDOR_STAFF),
            new DemoAccount(499, "admin.demo", UserRole.DEMO_ADMIN));

    private final CatalogRepository catalog;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final String demoPassword;

    public DemoFixtureSeeder(
            CatalogRepository catalog,
            JdbcTemplate jdbc,
            PasswordEncoder passwordEncoder,
            @Value("${xeats.demo.password:}") String demoPassword) {
        this.catalog = catalog;
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.demoPassword = demoPassword;
    }

    @Transactional
    public void seed() {
        if (demoPassword == null || demoPassword.isBlank()) {
            throw new IllegalStateException(
                    "Set XEATS_DEMO_PASSWORD before starting with the demo profile.");
        }

        List<FoodCourt> courts = List.of(
                new FoodCourt(id(201), "NORTH", "North Campus Food Court", "Asia/Kolkata", true),
                new FoodCourt(id(202), "SOUTH", "South Campus Food Court", "Asia/Kolkata", true));
        List<Vendor> vendors = List.of(
                new Vendor(id(211), id(201), "Campus Dining North", VendorServiceModel.CENTRALIZED, true),
                new Vendor(id(212), id(202), "Campus Dining South", VendorServiceModel.CENTRALIZED, true),
                new Vendor(id(221), id(201), "Independent Kitchen North", VendorServiceModel.INDEPENDENT, true),
                new Vendor(id(222), id(202), "Independent Kitchen South", VendorServiceModel.INDEPENDENT, true));
        List<CounterFixture> counters = List.of(
                fixture(301, 211, 501, "North Thali", "Veg Thali", "Vegetarian lunch plate", "Meals", 12900),
                fixture(302, 211, 502, "North Wraps", "Paneer Wrap", "Grilled paneer wrap", "Wraps", 8900),
                fixture(303, 221, 503, "North Dosa", "Masala Dosa", "Crisp dosa with potato masala", "South Indian", 9900),
                fixture(304, 212, 504, "South Rice", "Lemon Rice", "Seasoned lemon rice", "Rice", 7900),
                fixture(305, 222, 505, "South Bowls", "Chicken Rice Bowl", "Chicken and rice bowl", "Meals", 14900),
                fixture(306, 222, 506, "South Breakfast", "Idli Plate", "Steamed idli with sambar", "South Indian", 6900));

        courts.forEach(catalog::upsert);
        vendors.forEach(catalog::upsert);
        counters.forEach(fixture -> {
            catalog.upsert(fixture.counter());
            catalog.upsert(fixture.item());
            jdbc.update("""
                    INSERT IGNORE INTO counter_capacity_policy (
                        counter_id, revision, effective_at, slot_duration_minutes, total_meals_per_slot,
                        walk_in_reserve_percent, minimum_preparation_lead_seconds, maximum_advance_minutes,
                        service_seconds_per_meal)
                    VALUES (UUID_TO_BIN(?), 1, UTC_TIMESTAMP(6), 5, 6, 30, 120, 60, 75)
                    """, fixture.counter().id().toString());
            for (DayOfWeek weekday : OPEN_DAYS) {
                catalog.upsert(new CounterOpeningInterval(
                        fixture.counter().id(), weekday, OPENS_AT, CLOSES_AT));
            }
        });

        String passwordHash = stablePasswordHash();
        for (DemoAccount account : DEMO_ACCOUNTS) {
            ensureUser(account, passwordHash);
            jdbc.update("INSERT IGNORE INTO user_role (user_id, role) VALUES (UUID_TO_BIN(?), ?)",
                    account.id().toString(), account.role().name());
        }

        assign(id(411), id(301));
        assign(id(412), id(302));
        assign(id(414), id(303));
        assign(id(415), id(303));
        assign(id(413), id(304));
        assign(id(415), id(305));
        assign(id(414), id(306));
        assign(id(416), id(306));
    }

    private String stablePasswordHash() {
        for (DemoAccount account : DEMO_ACCOUNTS) {
            List<String> hashes = jdbc.query(
                    "SELECT password_hash FROM app_user WHERE id = UUID_TO_BIN(?) AND username = ?",
                    (result, row) -> result.getString(1), account.id().toString(), account.username());
            if (!hashes.isEmpty() && passwordEncoder.matches(demoPassword, hashes.getFirst())) {
                return hashes.getFirst();
            }
        }
        return passwordEncoder.encode(demoPassword);
    }

    private void ensureUser(DemoAccount account, String passwordHash) {
        String userId = account.id().toString();
        List<String> idsForUsername = jdbc.query(
                "SELECT BIN_TO_UUID(id) FROM app_user WHERE username = ?",
                (result, row) -> result.getString(1), account.username());
        if (!idsForUsername.isEmpty()) {
            if (!userId.equalsIgnoreCase(idsForUsername.getFirst())) {
                throw new IllegalStateException("A demo username is already used by a different account: "
                        + account.username());
            }
            jdbc.update("UPDATE app_user SET password_hash = ?, enabled = TRUE WHERE id = UUID_TO_BIN(?)",
                    passwordHash, userId);
            return;
        }

        Integer idCollision = jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE id = UUID_TO_BIN(?)", Integer.class, userId);
        if (idCollision != null && idCollision > 0) {
            throw new IllegalStateException("A deterministic demo account ID is already in use.");
        }
        jdbc.update("INSERT INTO app_user (id, username, password_hash, enabled) "
                        + "VALUES (UUID_TO_BIN(?), ?, ?, TRUE)",
                userId, account.username(), passwordHash);
    }

    private void assign(UUID userId, UUID counterId) {
        jdbc.update("INSERT IGNORE INTO staff_counter_assignment (user_id, counter_id) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?))",
                userId.toString(), counterId.toString());
    }

    private static CounterFixture fixture(
            int counterNumber,
            int vendorNumber,
            int itemNumber,
            String counterName,
            String itemName,
            String description,
            String category,
            long priceMinor) {
        UUID counterId = id(counterNumber);
        return new CounterFixture(
                new Counter(counterId, id(vendorNumber), counterName, "Collect at " + counterName,
                        true, true),
                new MenuItem(id(itemNumber), counterId, itemName, description, category,
                        priceMinor, "INR", MenuItemAvailability.AVAILABLE));
    }

    private static UUID id(int suffix) {
        return UUID.fromString("00000000-0000-4000-8000-%012d".formatted(suffix));
    }

    private record CounterFixture(Counter counter, MenuItem item) { }

    private record DemoAccount(int number, String username, UserRole role) {
        private UUID id() {
            return DemoFixtureSeeder.id(number);
        }
    }
}
