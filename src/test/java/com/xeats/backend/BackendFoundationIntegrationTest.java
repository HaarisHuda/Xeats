package com.xeats.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.URLEncoder;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.xeats.backend.common.web.ApiProblemException;
import com.xeats.backend.demo.DemoFixtureSeeder;
import com.xeats.backend.events.LiveEventPublisher;
import com.xeats.backend.events.LiveEventType;
import com.xeats.backend.identity.IdentityAuthorizationService;
import com.xeats.backend.identity.UserRole;
import com.xeats.backend.identity.XeatsPrincipal;
import com.xeats.backend.ordering.InvalidOrderTransitionException;
import com.xeats.backend.ordering.OrderLifecycleService;
import com.xeats.backend.ordering.OrderPersistenceRepository;
import com.xeats.backend.ordering.OrderStatus;
import com.xeats.backend.ordering.OrderVersionConflictException;
import com.xeats.backend.ordering.OrderNotFoundException;
import com.xeats.backend.ordering.OrderIdempotencyOperation;
import com.xeats.backend.ordering.OrderPersistenceModels.IdempotencyRecord;
import com.xeats.backend.ordering.OrderPersistenceModels.OrderRecord;

import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(OrderAnnotation.class)
class BackendFoundationIntegrationTest {
    private static final Pattern RESPONSE_ID_PATTERN = Pattern.compile("\"id\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern RECOMMENDED_ID_PATTERN = Pattern.compile(
            "\"recommendedWindowId\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern CSRF_TOKEN_PATTERN = Pattern.compile("\"token\":\"([^\"]+)\"");
    private static final Pattern PICKUP_CODE_PATTERN = Pattern.compile(
            "\"code\":\"([0-9A-HJKMNP-TV-Z]{10})\"");
    private static final UUID FOOD_COURT_ID = UUID.fromString("00000000-0000-4000-8000-000000000101");
    private static final UUID VENDOR_ID = UUID.fromString("00000000-0000-4000-8000-000000000102");
    private static final UUID COUNTER_ID = UUID.fromString("00000000-0000-4000-8000-000000000103");
    private static final UUID EMPLOYEE_ID = UUID.fromString("00000000-0000-4000-8000-000000000104");
    private static final UUID STAFF_ID = UUID.fromString("00000000-0000-4000-8000-000000000105");
    private static final UUID OTHER_COUNTER_ID = UUID.fromString("00000000-0000-4000-8000-000000000107");
    private static final UUID DEMO_STAFF_NORTH_THALI_ID = UUID.fromString("00000000-0000-4000-8000-000000000411");
    private static final UUID DEMO_STAFF_NORTH_WRAPS_ID = UUID.fromString("00000000-0000-4000-8000-000000000412");
    private static final UUID DEMO_EMPLOYEE_ID = UUID.fromString("00000000-0000-4000-8000-000000000401");
    private static final UUID DEMO_NORTH_COURT_ID = UUID.fromString("00000000-0000-4000-8000-000000000201");
    private static final UUID DEMO_SOUTH_COURT_ID = UUID.fromString("00000000-0000-4000-8000-000000000202");
    private static final UUID DEMO_NORTH_CENTRAL_VENDOR_ID = UUID.fromString("00000000-0000-4000-8000-000000000211");
    private static final UUID DEMO_NORTH_INDEPENDENT_VENDOR_ID = UUID.fromString("00000000-0000-4000-8000-000000000221");
    private static final UUID DEMO_SOUTH_INDEPENDENT_VENDOR_ID = UUID.fromString("00000000-0000-4000-8000-000000000222");
    private static final UUID DEMO_NORTH_THALI_COUNTER_ID = UUID.fromString("00000000-0000-4000-8000-000000000301");
    private static final UUID DEMO_NORTH_WRAPS_COUNTER_ID = UUID.fromString("00000000-0000-4000-8000-000000000302");
    private static final UUID DEMO_NORTH_DOSA_COUNTER_ID = UUID.fromString("00000000-0000-4000-8000-000000000303");
    private static final UUID DEMO_SOUTH_LEMON_RICE_COUNTER_ID = UUID.fromString("00000000-0000-4000-8000-000000000304");
    private static final UUID DEMO_SOUTH_BOWLS_COUNTER_ID = UUID.fromString("00000000-0000-4000-8000-000000000305");
    private static final UUID DEMO_SOUTH_IDLI_COUNTER_ID = UUID.fromString("00000000-0000-4000-8000-000000000306");
    private static final UUID EXTRA_DEMO_EMPLOYEE_ID = UUID.fromString("00000000-0000-4000-8000-000000000404");
    private static final UUID REDEMPTION_TEST_STAFF_ID = UUID.fromString("00000000-0000-4000-8000-000000000417");
    private static final UUID LIVE_TEST_EMPLOYEE_ID = UUID.fromString("00000000-0000-4000-8000-000000000418");

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2026-10-20T05:55:00Z"), ZoneOffset.UTC);
        }
    }

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11")
            .withDatabaseName("xeats")
            .withUsername("xeats_test")
            .withPassword("test-only-password")
            .withCommand("--log-bin-trust-function-creators=1");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl()
                + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("xeats.demo.password", () -> "integration-test-only-password");
    }

    @Value("${local.server.port}")
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private IdentityAuthorizationService authorization;

    @Autowired
    private DemoFixtureSeeder demoFixtureSeeder;

    @Autowired
    private OrderPersistenceRepository orderRepository;

    @Autowired
    private OrderLifecycleService orderLifecycle;

    @Autowired
    private LiveEventPublisher liveEvents;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @Order(1)
    void startsWithUtcClockAndRunsCatalogMigration() throws Exception {
        HttpResponse<String> liveness = get("/actuator/health/liveness");
        HttpResponse<String> readiness = get("/actuator/health/readiness");
        HttpResponse<String> protectedInfo = get("/actuator/info");

        assertThat(TimeZone.getDefault().getID()).isEqualTo("UTC");
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(liveness.statusCode()).isEqualTo(200);
        assertThat(liveness.body()).contains("\"status\":\"UP\"");
        assertThat(readiness.statusCode()).isEqualTo(200);
        assertThat(readiness.body()).contains("\"status\":\"UP\"");
        assertThat(readiness.body()).doesNotContain("jdbc:mysql", "xeats_app", "username", "password");
        assertThat(protectedInfo.statusCode()).isEqualTo(401);
        assertThat(protectedInfo.headers().firstValue("content-type").orElse("")).contains("application/problem+json");
        assertThat(protectedInfo.body()).contains("\"code\":\"UNAUTHENTICATED\"");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name IN "
                        + "('food_court', 'vendor', 'counter', 'menu_item')",
                Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = TRUE",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '2' AND success = TRUE",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '3' AND success = TRUE",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '4' AND success = TRUE",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '5' AND success = TRUE",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '7' AND success = TRUE",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'meal_order'
                    AND index_name IN ('ix_meal_order_counter_placed_report',
                        'ix_meal_order_counter_accepted_report', 'ix_meal_order_counter_collected_report',
                        'ix_meal_order_counter_expired_report')
                """, Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name IN "
                        + "('app_user', 'user_role', 'staff_counter_assignment', 'counter_opening_interval')",
                Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name IN "
                        + "('counter_capacity_policy', 'counter_change_event', 'pickup_slot')",
                Integer.class)).isEqualTo(3);
        assertThat(applicationContext.containsBean("demoFixtureRunner")).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class)).isZero();
    }

    @Test
    @Order(2)
    void seedsRepeatableSyntheticCatalogAndIdentityFixtures() {
        demoFixtureSeeder.seed();
        FixtureCounts firstSeed = fixtureCounts();

        demoFixtureSeeder.seed();
        FixtureCounts secondSeed = fixtureCounts();

        assertThat(secondSeed).isEqualTo(firstSeed);
        assertThat(secondSeed.foodCourts()).isEqualTo(2);
        assertThat(secondSeed.vendors()).isEqualTo(4);
        assertThat(secondSeed.counters()).isEqualTo(6);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM counter_capacity_policy WHERE revision = 1", Integer.class)).isEqualTo(6);
        assertThat(secondSeed.items()).isEqualTo(6);
        assertThat(secondSeed.users()).isEqualTo(10);
        assertThat(secondSeed.assignments()).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM vendor WHERE service_model = 'CENTRALIZED'", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM vendor WHERE service_model = 'INDEPENDENT'", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT c.id FROM counter c
                    LEFT JOIN menu_item m ON m.counter_id = c.id
                    GROUP BY c.id HAVING COUNT(m.id) <> 1
                ) AS invalid_counter_items
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM staff_counter_assignment WHERE counter_id = UUID_TO_BIN(?)",
                Integer.class, "00000000-0000-4000-8000-000000000303")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM counter_opening_interval", Integer.class))
                .isEqualTo(36);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM counter_opening_interval WHERE iso_weekday = 7", Integer.class)).isZero();
    }

    @Test
    @Order(3)
    void employeeDiscoveryReturnsOnlyActivePublicCatalogData() throws Exception {
        HttpResponse<String> anonymousCourts = get("/api/v1/food-courts");
        assertThat(anonymousCourts.statusCode()).isEqualTo(401);

        HttpClient employee = newSessionClient();
        String employeeCsrf = csrfToken(get(employee, "/api/v1/auth/csrf"));
        HttpResponse<String> employeeLogin = post(employee, "/api/v1/auth/login",
                "{\"username\":\"employee.demo.01\",\"password\":\"integration-test-only-password\"}",
                employeeCsrf);
        assertThat(employeeLogin.statusCode()).isEqualTo(200);

        HttpResponse<String> courts = get(employee, "/api/v1/food-courts");
        assertThat(courts.statusCode()).isEqualTo(200);
        assertThat(courts.body()).contains("North Campus Food Court", "South Campus Food Court", "Asia/Kolkata");
        assertThat(courts.body().indexOf("NORTH")).isLessThan(courts.body().indexOf("SOUTH"));
        assertThat(courts.body()).doesNotContain("password", "username", "assignedCounterIds", "queueCounts");

        HttpResponse<String> northVendors = get(employee,
                "/api/v1/food-courts/" + DEMO_NORTH_COURT_ID + "/vendors");
        assertThat(northVendors.statusCode()).isEqualTo(200);
        assertThat(northVendors.body()).contains("Campus Dining North", "Independent Kitchen North");
        assertThat(northVendors.body()).doesNotContain("serviceModel", "staffCounterAssignment");

        HttpResponse<String> northCentralCounters = get(employee,
                "/api/v1/vendors/" + DEMO_NORTH_CENTRAL_VENDOR_ID + "/counters");
        assertThat(northCentralCounters.statusCode()).isEqualTo(200);
        assertThat(northCentralCounters.body()).contains(
                "North Thali", "Veg Thali", "North Wraps", "Paneer Wrap", "Collect at North Thali",
                "amountMinor", "12900", "INR", "acceptingOnlineOrders", "pickupInstructions");

        jdbc.update("UPDATE menu_item SET availability = 'SOLD_OUT' WHERE counter_id = UUID_TO_BIN(?)",
                DEMO_NORTH_DOSA_COUNTER_ID.toString());
        HttpResponse<String> soldOutCounter = get(employee,
                "/api/v1/counters/" + DEMO_NORTH_DOSA_COUNTER_ID);
        assertThat(soldOutCounter.statusCode()).isEqualTo(200);
        assertThat(soldOutCounter.body()).contains("Masala Dosa", "SOLD_OUT", "9900", "Collect at North Dosa");
        assertThat(soldOutCounter.body()).doesNotContain(
                "serviceModel", "staffCounterAssignment", "password_hash", "employeeDisplayLabel");

        jdbc.update("UPDATE counter SET accepting_online_orders = FALSE WHERE id = UUID_TO_BIN(?)",
                DEMO_SOUTH_LEMON_RICE_COUNTER_ID.toString());
        HttpResponse<String> pausedCounter = get(employee,
                "/api/v1/vendors/00000000-0000-4000-8000-000000000212/counters");
        assertThat(pausedCounter.statusCode()).isEqualTo(200);
        assertThat(pausedCounter.body()).contains("Lemon Rice", "\"acceptingOnlineOrders\":false");

        jdbc.update("UPDATE food_court SET active = FALSE WHERE id = UUID_TO_BIN(?)",
                DEMO_SOUTH_COURT_ID.toString());
        HttpResponse<String> filteredCourts = get(employee, "/api/v1/food-courts");
        assertThat(filteredCourts.body()).contains("North Campus Food Court")
                .doesNotContain("South Campus Food Court");
        assertThat(get(employee, "/api/v1/food-courts/" + DEMO_SOUTH_COURT_ID + "/vendors").body())
                .contains("\"items\":[]");
        jdbc.update("UPDATE food_court SET active = TRUE WHERE id = UUID_TO_BIN(?)",
                DEMO_SOUTH_COURT_ID.toString());

        jdbc.update("UPDATE vendor SET active = FALSE WHERE id = UUID_TO_BIN(?)",
                DEMO_NORTH_INDEPENDENT_VENDOR_ID.toString());
        assertThat(get(employee, "/api/v1/vendors/" + DEMO_NORTH_INDEPENDENT_VENDOR_ID + "/counters").body())
                .contains("\"items\":[]");
        jdbc.update("UPDATE vendor SET active = TRUE WHERE id = UUID_TO_BIN(?)",
                DEMO_NORTH_INDEPENDENT_VENDOR_ID.toString());

        jdbc.update("UPDATE counter SET active = FALSE WHERE id = UUID_TO_BIN(?)",
                DEMO_SOUTH_IDLI_COUNTER_ID.toString());
        assertThat(get(employee, "/api/v1/counters/" + DEMO_SOUTH_IDLI_COUNTER_ID).statusCode()).isEqualTo(404);
        assertThat(get(employee, "/api/v1/vendors/" + DEMO_SOUTH_INDEPENDENT_VENDOR_ID + "/counters").body())
                .doesNotContain("Idli Plate");
        jdbc.update("UPDATE counter SET active = TRUE WHERE id = UUID_TO_BIN(?)",
                DEMO_SOUTH_IDLI_COUNTER_ID.toString());

        HttpClient staff = newSessionClient();
        String staffCsrf = csrfToken(get(staff, "/api/v1/auth/csrf"));
        HttpResponse<String> staffLogin = post(staff, "/api/v1/auth/login",
                "{\"username\":\"staff.north.thali\",\"password\":\"integration-test-only-password\"}",
                staffCsrf);
        assertThat(staffLogin.statusCode()).isEqualTo(200);
        assertThat(get(staff, "/api/v1/food-courts").statusCode()).isEqualTo(200);
    }

    @Test
    @Order(4)
    void authenticatesEmployeesAndStaffWithCsrfAndScopedAuthorization() throws Exception {
        seedIdentityFixture();

        HttpClient employee = newSessionClient();
        HttpResponse<String> unauthenticatedMe = get(newSessionClient(), "/api/v1/auth/me");
        assertThat(unauthenticatedMe.statusCode()).isEqualTo(401);
        assertThat(unauthenticatedMe.body()).contains("\"code\":\"UNAUTHENTICATED\"");

        HttpResponse<String> csrfResponse = get(employee, "/api/v1/auth/csrf", "Origin", "http://localhost:4200");
        assertThat(csrfResponse.statusCode()).isEqualTo(200);
        assertThat(csrfResponse.headers().firstValue("access-control-allow-origin").orElse("") )
                .isEqualTo("http://localhost:4200");
        assertThat(csrfResponse.headers().firstValue("access-control-allow-credentials").orElse(""))
                .isEqualTo("true");
        String initialSessionId = cookieValue(csrfResponse, "JSESSIONID");
        String csrf = csrfToken(csrfResponse);

        HttpResponse<String> noCsrfLogin = post(employee, "/api/v1/auth/login",
                "{\"username\":\"employee.one\",\"password\":\"demo-password\"}", null);
        assertThat(noCsrfLogin.statusCode()).isEqualTo(403);
        assertThat(noCsrfLogin.body()).contains("\"code\":\"FORBIDDEN\"");
        HttpResponse<String> invalidCsrfLogin = post(employee, "/api/v1/auth/login",
                "{\"username\":\"employee.one\",\"password\":\"demo-password\"}", "invalid-token");
        assertThat(invalidCsrfLogin.statusCode()).isEqualTo(403);

        HttpResponse<String> wrongPassword = post(employee, "/api/v1/auth/login",
                "{\"username\":\"employee.one\",\"password\":\"wrong-password\"}", csrf);
        HttpResponse<String> unknownUsername = post(employee, "/api/v1/auth/login",
                "{\"username\":\"missing.user\",\"password\":\"wrong-password\"}", csrf);
        assertThat(wrongPassword.statusCode()).isEqualTo(401);
        assertThat(unknownUsername.statusCode()).isEqualTo(401);
        assertThat(unknownUsername.body()).isEqualTo(wrongPassword.body());

        HttpResponse<String> employeeLogin = post(employee, "/api/v1/auth/login",
                "{\"username\":\"EMPLOYEE.ONE\",\"password\":\"demo-password\"}", csrf);
        assertThat(employeeLogin.statusCode()).isEqualTo(200);
        assertThat(employeeLogin.body()).contains("\"username\":\"employee.one\"");
        assertThat(employeeLogin.body()).contains("\"roles\":[\"EMPLOYEE\"]");
        assertThat(employeeLogin.body()).contains("\"assignedCounterIds\":[]");
        assertThat(cookieValue(employeeLogin, "JSESSIONID")).isNotEqualTo(initialSessionId);

        HttpResponse<String> employeeMe = get(employee, "/api/v1/auth/me");
        assertThat(employeeMe.statusCode()).isEqualTo(200);
        assertThat(employeeMe.body()).contains(EMPLOYEE_ID.toString());
        assertThat(get(employee, "/api/v1/me/orders").statusCode()).isEqualTo(200);
        assertThat(get(employee, "/api/v1/staff/counters/" + COUNTER_ID + "/orders").statusCode())
                .isEqualTo(403);
        assertThat(get(employee, "/api/v1/admin/food-courts").statusCode()).isEqualTo(403);
        jdbc.update("DELETE FROM user_role WHERE user_id = UUID_TO_BIN(?) AND role = 'EMPLOYEE'",
                EMPLOYEE_ID.toString());
        assertThat(get(employee, "/api/v1/me/orders").statusCode()).isEqualTo(403);
        jdbc.update("INSERT INTO user_role (user_id, role) VALUES (UUID_TO_BIN(?), 'EMPLOYEE')",
                EMPLOYEE_ID.toString());
        assertThat(get(employee, "/api/v1/me/orders").statusCode()).isEqualTo(200);

        HttpResponse<String> forbiddenOrigin = get(employee, "/api/v1/auth/csrf", "Origin", "https://unlisted.invalid");
        assertThat(forbiddenOrigin.headers().firstValue("access-control-allow-origin")).isEmpty();

        HttpResponse<String> logoutWithoutCsrf = post(employee, "/api/v1/auth/logout", "", null);
        assertThat(logoutWithoutCsrf.statusCode()).isEqualTo(403);
        String logoutCsrf = csrfToken(get(employee, "/api/v1/auth/csrf"));
        HttpResponse<String> logout = post(employee, "/api/v1/auth/logout", "", logoutCsrf);
        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(get(employee, "/api/v1/auth/me").statusCode()).isEqualTo(401);

        HttpClient staff = newSessionClient();
        String staffCsrf = csrfToken(get(staff, "/api/v1/auth/csrf"));
        HttpResponse<String> staffLogin = post(staff, "/api/v1/auth/login",
                "{\"username\":\"staff.one\",\"password\":\"demo-password\"}", staffCsrf);
        assertThat(staffLogin.statusCode()).isEqualTo(200);
        assertThat(staffLogin.body()).contains("\"roles\":[\"VENDOR_STAFF\"]");
        assertThat(staffLogin.body()).contains(COUNTER_ID.toString());
        assertThat(get(staff, "/api/v1/staff/counters/" + COUNTER_ID + "/orders").statusCode()).isEqualTo(404);
        assertThat(get(staff, "/api/v1/admin/food-courts").statusCode()).isEqualTo(403);

        XeatsPrincipal staffPrincipal = new XeatsPrincipal(STAFF_ID, "staff.one", "unused", Set.of(UserRole.VENDOR_STAFF), true);
        authorization.requireCounterAssignment(staffPrincipal, COUNTER_ID);
        jdbc.update("DELETE FROM staff_counter_assignment WHERE user_id = UUID_TO_BIN(?) AND counter_id = UUID_TO_BIN(?)",
                STAFF_ID.toString(), COUNTER_ID.toString());
        assertThatThrownBy(() -> authorization.requireCounterAssignment(staffPrincipal, COUNTER_ID))
                .isInstanceOf(ApiProblemException.class)
                .hasMessage("This action is not permitted.");
        jdbc.update("INSERT INTO staff_counter_assignment (user_id, counter_id) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?))",
                STAFF_ID.toString(), COUNTER_ID.toString());
        assertThatThrownBy(() -> authorization.requireCounterAssignment(staffPrincipal, OTHER_COUNTER_ID))
                .isInstanceOf(ApiProblemException.class)
                .hasMessage("This action is not permitted.");
        XeatsPrincipal employeePrincipal = new XeatsPrincipal(EMPLOYEE_ID, "employee.one", "unused", Set.of(UserRole.EMPLOYEE), true);
        authorization.requireOrderOwner(employeePrincipal, EMPLOYEE_ID);
        assertThatThrownBy(() -> authorization.requireOrderOwner(employeePrincipal, STAFF_ID))
                .isInstanceOf(ApiProblemException.class)
                .hasMessage("The requested resource was not found.");

        HttpClient admin = newSessionClient();
        String adminCsrf = csrfToken(get(admin, "/api/v1/auth/csrf"));
        HttpResponse<String> adminLogin = post(admin, "/api/v1/auth/login",
                "{\"username\":\"admin.demo\",\"password\":\"integration-test-only-password\"}", adminCsrf);
        assertThat(adminLogin.statusCode()).isEqualTo(200);
        assertThat(get(admin, "/api/v1/admin/not-yet-implemented").statusCode()).isEqualTo(404);
    }

    @Test
    @Order(5)
    void catalogAdministrationAndCounterControlsEnforceScopeAndRetainHistory() throws Exception {
        HttpClient employee = newSessionClient();
        String employeeCsrf = csrfToken(get(employee, "/api/v1/auth/csrf"));
        HttpResponse<String> employeeLogin = post(employee, "/api/v1/auth/login",
                "{\"username\":\"employee.demo.01\",\"password\":\"integration-test-only-password\"}",
                employeeCsrf);
        assertThat(employeeLogin.statusCode()).isEqualTo(200);
        String employeeMutationCsrf = csrfToken(get(employee, "/api/v1/auth/csrf"));
        assertThat(post(employee, "/api/v1/admin/food-courts",
                "{\"code\":\"OPS-TEST\",\"name\":\"Unauthorized\",\"timeZone\":\"Asia/Kolkata\"}",
                employeeMutationCsrf).statusCode()).isEqualTo(403);

        HttpClient admin = newSessionClient();
        String adminCsrf = csrfToken(get(admin, "/api/v1/auth/csrf"));
        HttpResponse<String> adminLogin = post(admin, "/api/v1/auth/login",
                "{\"username\":\"admin.demo\",\"password\":\"integration-test-only-password\"}", adminCsrf);
        assertThat(adminLogin.statusCode()).isEqualTo(200);
        String adminMutationCsrf = csrfToken(get(admin, "/api/v1/auth/csrf"));

        String foodCourtBody = "{\"code\":\"OPS-TEST\",\"name\":\"Operations Test Court\","
                + "\"timeZone\":\"Asia/Kolkata\"}";
        HttpResponse<String> foodCourtCreated = post(admin, "/api/v1/admin/food-courts",
                foodCourtBody, adminMutationCsrf);
        assertThat(foodCourtCreated.statusCode()).isEqualTo(201);
        UUID createdFoodCourtId = responseId(foodCourtCreated);
        assertThat(foodCourtCreated.body()).contains("Operations Test Court");
        HttpResponse<String> duplicateFoodCourt = post(admin, "/api/v1/admin/food-courts",
                foodCourtBody, adminMutationCsrf);
        assertThat(duplicateFoodCourt.statusCode()).isEqualTo(409);
        assertThat(duplicateFoodCourt.body()).contains("\"code\":\"CONFLICT\"");

        HttpResponse<String> vendorCreated = post(admin,
                "/api/v1/admin/food-courts/" + createdFoodCourtId + "/vendors",
                "{\"name\":\"Operations Vendor\"}", adminMutationCsrf);
        assertThat(vendorCreated.statusCode()).isEqualTo(201);
        UUID createdVendorId = responseId(vendorCreated);
        assertThat(jdbc.queryForObject("SELECT service_model FROM vendor WHERE id = UUID_TO_BIN(?)",
                String.class, createdVendorId.toString())).isEqualTo("INDEPENDENT");

        String counterBody = """
                {
                  "name":"Operations Counter",
                  "pickupInstructions":"Collect at the counter",
                  "item":{"name":"Lunch Plate","description":"Rice and vegetables","category":"Meals",
                    "price":{"amountMinor":12500,"currency":"INR"}},
                  "capacityPolicy":{"slotDurationMinutes":15,"totalMealsPerSlot":24,
                    "walkInReservePercent":25,"minimumPreparationLeadSeconds":120,
                    "maximumAdvanceMinutes":240,"serviceSecondsPerMeal":45},
                  "openingHours":{"timeZone":"Asia/Kolkata","intervals":[
                    {"day":"MONDAY","opensAt":"11:00:00","closesAt":"14:00:00"}]}
                }
                """;
        HttpResponse<String> counterCreated = post(admin,
                "/api/v1/admin/vendors/" + createdVendorId + "/counters", counterBody, adminMutationCsrf);
        assertThat(counterCreated.statusCode()).isEqualTo(201);
        UUID createdCounterId = responseId(counterCreated);
        assertThat(counterCreated.body()).contains("\"active\":true", "Lunch Plate", "amountMinor\":12500");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM menu_item WHERE counter_id = UUID_TO_BIN(?)",
                Integer.class, createdCounterId.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM counter_capacity_policy WHERE counter_id = UUID_TO_BIN(?) AND revision = 1",
                Integer.class, createdCounterId.toString())).isEqualTo(1);

        HttpResponse<String> wrongRoleAssignment = post(admin,
                "/api/v1/admin/counters/" + createdCounterId + "/staff",
                "{\"userId\":\"" + DEMO_EMPLOYEE_ID + "\"}", adminMutationCsrf);
        assertThat(wrongRoleAssignment.statusCode()).isEqualTo(422);

        HttpClient unassignedStaff = loginStaff("staff.north.wraps");
        String unassignedCsrf = csrfToken(get(unassignedStaff, "/api/v1/auth/csrf"));
        assertThat(patch(unassignedStaff,
                "/api/v1/staff/counters/" + createdCounterId + "/item",
                "{\"name\":\"Unauthorized edit\",\"price\":{\"amountMinor\":1,\"currency\":\"INR\"}}",
                unassignedCsrf).statusCode()).isEqualTo(403);

        HttpResponse<String> firstAssignment = post(admin,
                "/api/v1/admin/counters/" + createdCounterId + "/staff",
                "{\"userId\":\"" + DEMO_STAFF_NORTH_THALI_ID + "\"}", adminMutationCsrf);
        assertThat(firstAssignment.statusCode()).isEqualTo(204);
        assertThat(post(admin, "/api/v1/admin/counters/" + createdCounterId + "/staff",
                "{\"userId\":\"" + DEMO_STAFF_NORTH_THALI_ID + "\"}", adminMutationCsrf)
                .statusCode()).isEqualTo(204);
        assertThat(post(admin, "/api/v1/admin/counters/" + createdCounterId + "/staff",
                "{\"userId\":\"" + DEMO_STAFF_NORTH_WRAPS_ID + "\"}", adminMutationCsrf)
                .statusCode()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staff_counter_assignment WHERE counter_id = UUID_TO_BIN(?)",
                Integer.class, createdCounterId.toString())).isEqualTo(2);

        HttpClient assignedStaff = loginStaff("staff.north.thali");
        String staffCsrf = csrfToken(get(assignedStaff, "/api/v1/auth/csrf"));
        HttpResponse<String> itemUpdated = patch(assignedStaff,
                "/api/v1/staff/counters/" + createdCounterId + "/item",
                "{\"name\":\"Updated Lunch Plate\",\"description\":\"Freshly prepared\","
                        + "\"category\":\"Lunch\",\"price\":{\"amountMinor\":13000,\"currency\":\"INR\"}}",
                staffCsrf);
        assertThat(itemUpdated.statusCode()).isEqualTo(200);
        assertThat(itemUpdated.body()).contains("Updated Lunch Plate", "amountMinor\":13000");

        HttpResponse<String> soldOut = put(assignedStaff,
                "/api/v1/staff/counters/" + createdCounterId + "/availability",
                "{\"availability\":\"SOLD_OUT\"}", staffCsrf);
        assertThat(soldOut.statusCode()).isEqualTo(200);
        assertThat(soldOut.body()).contains("\"availability\":\"SOLD_OUT\"");
        HttpResponse<String> orderingClosed = put(assignedStaff,
                "/api/v1/staff/counters/" + createdCounterId + "/ordering",
                "{\"acceptingOnlineOrders\":false}", staffCsrf);
        assertThat(orderingClosed.statusCode()).isEqualTo(200);
        assertThat(orderingClosed.body()).contains("\"acceptingOnlineOrders\":false");
        assertThat(put(admin, "/api/v1/staff/counters/" + createdCounterId + "/ordering",
                "{\"acceptingOnlineOrders\":false}", adminMutationCsrf).statusCode()).isEqualTo(200);

        String replacementHours = "{\"timeZone\":\"Asia/Kolkata\",\"intervals\":["
                + "{\"day\":\"TUESDAY\",\"opensAt\":\"22:00:00\",\"closesAt\":\"02:00:00\"},"
                + "{\"day\":\"FRIDAY\",\"opensAt\":\"11:00:00\",\"closesAt\":\"12:00:00\"}]}";
        HttpResponse<String> hoursUpdated = put(assignedStaff,
                "/api/v1/staff/counters/" + createdCounterId + "/opening-hours", replacementHours, staffCsrf);
        assertThat(hoursUpdated.statusCode()).isEqualTo(200);
        assertThat(hoursUpdated.body()).contains("TUESDAY", "22:00:00", "02:00:00");
        String overlappingHours = "{\"timeZone\":\"Asia/Kolkata\",\"intervals\":["
                + "{\"day\":\"TUESDAY\",\"opensAt\":\"22:00:00\",\"closesAt\":\"02:00:00\"},"
                + "{\"day\":\"WEDNESDAY\",\"opensAt\":\"01:00:00\",\"closesAt\":\"03:00:00\"}]}";
        HttpResponse<String> rejectedHours = put(assignedStaff,
                "/api/v1/staff/counters/" + createdCounterId + "/opening-hours", overlappingHours, staffCsrf);
        assertThat(rejectedHours.statusCode()).isEqualTo(422);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM counter_opening_interval WHERE counter_id = UUID_TO_BIN(?)",
                Integer.class, createdCounterId.toString())).isEqualTo(2);

        assertThat(delete(admin,
                "/api/v1/admin/counters/" + createdCounterId + "/staff/" + DEMO_STAFF_NORTH_WRAPS_ID,
                adminMutationCsrf).statusCode()).isEqualTo(204);
        assertThat(delete(admin,
                "/api/v1/admin/counters/" + createdCounterId + "/staff/" + DEMO_STAFF_NORTH_WRAPS_ID,
                adminMutationCsrf).statusCode()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staff_counter_assignment WHERE counter_id = UUID_TO_BIN(?)",
                Integer.class, createdCounterId.toString())).isEqualTo(1);

        HttpResponse<String> deactivated = put(admin,
                "/api/v1/admin/counters/" + createdCounterId + "/active",
                "{\"active\":false}", adminMutationCsrf);
        assertThat(deactivated.statusCode()).isEqualTo(200);
        assertThat(deactivated.body()).contains("\"active\":false");
        assertThat(get(assignedStaff, "/api/v1/counters/" + createdCounterId).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM menu_item WHERE counter_id = UUID_TO_BIN(?)",
                Integer.class, createdCounterId.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM counter_capacity_policy WHERE counter_id = UUID_TO_BIN(?)",
                Integer.class, createdCounterId.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM counter_change_event WHERE counter_id = UUID_TO_BIN(?)",
                Integer.class, createdCounterId.toString())).isEqualTo(9);
        assertThat(jdbc.queryForObject("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(new_value, '$.capacityPolicy.slotDurationMinutes'))
                FROM counter_change_event
                WHERE counter_id = UUID_TO_BIN(?) AND field_name = 'COUNTER_CREATED'
                """, String.class, createdCounterId.toString())).isEqualTo("15");
    }

    @Test
    @Order(6)
    void capacityQuotesMaterializeIdempotentlyWithoutReservingAndHonorPolicySnapshots() throws Exception {
        HttpClient employee = newSessionClient();
        String employeeCsrf = csrfToken(get(employee, "/api/v1/auth/csrf"));
        assertThat(post(employee, "/api/v1/auth/login",
                "{\"username\":\"employee.demo.02\",\"password\":\"integration-test-only-password\"}",
                employeeCsrf).statusCode()).isEqualTo(200);

        HttpClient staff = loginStaff("staff.north.thali");
        String staffCsrf = csrfToken(get(staff, "/api/v1/auth/csrf"));
        String fullDayHours = fullDayHours();
        assertThat(put(staff, "/api/v1/staff/counters/" + DEMO_NORTH_THALI_COUNTER_ID + "/opening-hours",
                fullDayHours, staffCsrf).statusCode()).isEqualTo(200);
        String initialPolicy = capacityPolicy(5, 6, 30, 120, 60, 75);
        assertThat(put(staff,
                "/api/v1/staff/counters/" + DEMO_NORTH_THALI_COUNTER_ID + "/capacity-policy",
                initialPolicy, staffCsrf).statusCode()).isEqualTo(200);

        String optionsPath = "/api/v1/counters/" + DEMO_NORTH_THALI_COUNTER_ID + "/order-options";
        HttpResponse<String> firstQuote = get(employee, optionsPath);
        assertThat(firstQuote.statusCode()).isEqualTo(200);
        assertThat(firstQuote.body()).contains("\"onlineCapacity\":4", "\"remaining\":4");
        assertThat(firstQuote.body()).contains("\"recommendedWindowId\"");

        List<CompletableFuture<HttpResponse<String>>> concurrentQuotes = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            concurrentQuotes.add(employee.sendAsync(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + optionsPath)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()));
        }
        for (CompletableFuture<HttpResponse<String>> quote : concurrentQuotes) {
            HttpResponse<String> response = quote.join();
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(firstQuote.body());
        }

        int materializedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pickup_slot WHERE counter_id = UUID_TO_BIN(?)",
                Integer.class, DEMO_NORTH_THALI_COUNTER_ID.toString());
        assertThat(materializedCount).isPositive();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM pickup_slot
                WHERE counter_id = UUID_TO_BIN(?) AND confirmed_allocation_count <> 0
                """, Integer.class, DEMO_NORTH_THALI_COUNTER_ID.toString())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT start_at FROM pickup_slot WHERE counter_id = UUID_TO_BIN(?)
                    GROUP BY start_at HAVING COUNT(*) > 1
                ) duplicates
                """, Integer.class, DEMO_NORTH_THALI_COUNTER_ID.toString())).isZero();

        UUID fullWindowId = UUID.fromString(jdbc.queryForObject("""
                SELECT BIN_TO_UUID(id) FROM pickup_slot
                WHERE counter_id = UUID_TO_BIN(?) ORDER BY start_at LIMIT 1
                """, String.class, DEMO_NORTH_THALI_COUNTER_ID.toString()));
        jdbc.update("""
                UPDATE pickup_slot SET confirmed_allocation_count = online_capacity_snapshot
                WHERE id = UUID_TO_BIN(?)
                """, fullWindowId.toString());
        HttpResponse<String> quoteWithFullWindow = get(employee, optionsPath);
        assertThat(quoteWithFullWindow.statusCode()).isEqualTo(200);
        assertThat(quoteWithFullWindow.body()).contains(
                "\"id\":\"" + fullWindowId + "\"", "\"reason\":\"WINDOW_FULL\"");
        Matcher recommendation = RECOMMENDED_ID_PATTERN.matcher(quoteWithFullWindow.body());
        assertThat(recommendation.find()).isTrue();
        assertThat(recommendation.group(1)).isNotEqualTo(fullWindowId.toString());
        assertThat(jdbc.queryForObject("""
                SELECT online_capacity_snapshot FROM pickup_slot WHERE id = UUID_TO_BIN(?)
                """, Integer.class, fullWindowId.toString())).isEqualTo(4);

        HttpResponse<String> revisedPolicy = put(staff,
                "/api/v1/staff/counters/" + DEMO_NORTH_THALI_COUNTER_ID + "/capacity-policy",
                capacityPolicy(5, 10, 0, 120, 60, 75), staffCsrf);
        assertThat(revisedPolicy.statusCode()).isEqualTo(200);
        assertThat(revisedPolicy.body()).contains("\"revision\":2", "\"onlineCapacityPerSlot\":10");
        assertThat(jdbc.queryForObject("""
                SELECT CONCAT(policy_revision, ':', total_capacity_snapshot, ':', online_capacity_snapshot)
                FROM pickup_slot WHERE id = UUID_TO_BIN(?)
                """, String.class, fullWindowId.toString())).isEqualTo("1:6:4");
        assertThat(jdbc.queryForObject("""
                SELECT confirmed_allocation_count FROM pickup_slot WHERE id = UUID_TO_BIN(?)
                """, Integer.class, fullWindowId.toString())).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM counter_change_event
                WHERE counter_id = UUID_TO_BIN(?) AND field_name = 'CAPACITY_POLICY'
                """, Integer.class, DEMO_NORTH_THALI_COUNTER_ID.toString())).isEqualTo(1);

        HttpResponse<String> capacityStatus = get(staff,
                "/api/v1/staff/counters/" + DEMO_NORTH_THALI_COUNTER_ID + "/capacity");
        assertThat(capacityStatus.statusCode()).isEqualTo(200);
        assertThat(capacityStatus.body()).contains("\"totalMeals\":6", "\"walkInReserved\":2",
                "\"onlineCapacity\":4");

        HttpClient independentStaff = loginStaff("staff.independent.01");
        String independentCsrf = csrfToken(get(independentStaff, "/api/v1/auth/csrf"));
        assertThat(put(independentStaff,
                "/api/v1/staff/counters/" + DEMO_SOUTH_IDLI_COUNTER_ID + "/opening-hours",
                fullDayHours, independentCsrf).statusCode()).isEqualTo(200);
        assertThat(put(independentStaff,
                "/api/v1/staff/counters/" + DEMO_SOUTH_IDLI_COUNTER_ID + "/capacity-policy",
                capacityPolicy(5, 6, 100, 120, 60, 75), independentCsrf).statusCode()).isEqualTo(200);
        HttpResponse<String> zeroCapacity = get(employee,
                "/api/v1/counters/" + DEMO_SOUTH_IDLI_COUNTER_ID + "/order-options");
        assertThat(zeroCapacity.statusCode()).isEqualTo(200);
        assertThat(zeroCapacity.body()).contains("\"selectableWindows\":[]",
                "\"noOptionsReason\":\"NO_ONLINE_CAPACITY\"", "\"reason\":\"NO_ONLINE_CAPACITY\"");
    }

    @Test
    @Order(7)
    void orderPersistenceEnforcesClaimsTokensSnapshotsAndAtomicTerminalLifecycle() {
        UUID pickupSlotId = UUID.fromString(jdbc.queryForObject("""
                SELECT BIN_TO_UUID(id) FROM pickup_slot
                WHERE counter_id = UUID_TO_BIN(?) ORDER BY start_at LIMIT 1
                """, String.class, DEMO_NORTH_THALI_COUNTER_ID.toString()));
        UUID orderId = UUID.randomUUID();
        UUID competingOrderId = UUID.randomUUID();
        UUID expiringOrderId = UUID.randomUUID();
        UUID collectedOrderId = UUID.randomUUID();
        Instant now = clock.instant();

        createOrderFixture(orderId, DEMO_EMPLOYEE_ID, DEMO_NORTH_THALI_COUNTER_ID, pickupSlotId,
                "01ABCDEFGH", true);
        OrderRecord snapshot = orderRepository.findById(orderId).orElseThrow();
        assertThat(snapshot.itemName()).isEqualTo("Veg Thali");
        assertThat(snapshot.itemPriceMinor()).isEqualTo(12900);
        assertThat(snapshot.counterName()).isEqualTo("North Thali");
        assertThat(snapshot.pickupInstructions()).isEqualTo("Collect at North Thali");

        try {
            jdbc.update("UPDATE menu_item SET name = ?, price_minor = ? WHERE id = UUID_TO_BIN(?)",
                    "Renamed Thali", 14900, "00000000-0000-4000-8000-000000000501");
            OrderRecord afterEdit = orderRepository.findById(orderId).orElseThrow();
            assertThat(afterEdit.itemName()).isEqualTo("Veg Thali");
            assertThat(afterEdit.itemPriceMinor()).isEqualTo(12900);
        } finally {
            jdbc.update("UPDATE menu_item SET name = ?, price_minor = ? WHERE id = UUID_TO_BIN(?)",
                    "Veg Thali", 12900, "00000000-0000-4000-8000-000000000501");
        }

        createOrderFixture(competingOrderId, DEMO_EMPLOYEE_ID, DEMO_NORTH_THALI_COUNTER_ID, pickupSlotId,
                null, false);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO active_order_claim (employee_id, counter_id, order_id, claimed_at)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?), ?)
                """, DEMO_EMPLOYEE_ID.toString(), DEMO_NORTH_THALI_COUNTER_ID.toString(),
                competingOrderId.toString(), java.sql.Timestamp.from(now)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO pickup_token (order_id, code, issued_at)
                VALUES (UUID_TO_BIN(?), ?, ?)
                """, competingOrderId.toString(), "01ABCDEFGH", java.sql.Timestamp.from(now)))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID secondDemoEmployeeId = UUID.fromString("00000000-0000-4000-8000-000000000402");
        createOrderFixture(expiringOrderId, secondDemoEmployeeId, DEMO_NORTH_THALI_COUNTER_ID, pickupSlotId,
                "02ABCDEFGH", true);
        IdempotencyRecord idempotencyRecord = new IdempotencyRecord(
                secondDemoEmployeeId, OrderIdempotencyOperation.CREATE_ORDER, UUID.randomUUID(),
                "a".repeat(64), expiringOrderId, 201, now);
        orderRepository.insertIdempotencyRecord(idempotencyRecord);
        assertThat(orderRepository.findIdempotencyRecord(secondDemoEmployeeId, idempotencyRecord.key()))
                .contains(idempotencyRecord);
        int allocationsBefore = jdbc.queryForObject(
                "SELECT confirmed_allocation_count FROM pickup_slot WHERE id = UUID_TO_BIN(?)",
                Integer.class, pickupSlotId.toString());
        OrderRecord expired = orderLifecycle.transition(expiringOrderId, 1, OrderStatus.EXPIRED,
                secondDemoEmployeeId, "Service ended");
        assertThat(expired.status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(expired.version()).isEqualTo(2);
        assertThat(expired.expiredReason()).isEqualTo("Service ended");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM active_order_claim WHERE order_id = UUID_TO_BIN(?)",
                Integer.class, expiringOrderId.toString())).isZero();
        assertThat(orderRepository.lockPickupToken(expiringOrderId).orElseThrow().isRedeemable()).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT confirmed_allocation_count FROM pickup_slot WHERE id = UUID_TO_BIN(?)",
                Integer.class, pickupSlotId.toString())).isEqualTo(allocationsBefore);
        assertThat(orderRepository.findStatusEvents(expiringOrderId)).hasSize(2);
        assertThat(orderRepository.findIdempotencyRecord(secondDemoEmployeeId, idempotencyRecord.key()))
                .contains(idempotencyRecord);

        assertThatThrownBy(() -> orderLifecycle.transition(expiringOrderId, 2, OrderStatus.EXPIRED,
                secondDemoEmployeeId, "Repeated expiry"))
                .isInstanceOf(InvalidOrderTransitionException.class);
        assertThatThrownBy(() -> orderLifecycle.transition(orderId, 99, OrderStatus.PREPARING,
                DEMO_EMPLOYEE_ID, null))
                .isInstanceOf(OrderVersionConflictException.class);
        assertThatThrownBy(() -> orderLifecycle.transition(UUID.randomUUID(), 1, OrderStatus.PREPARING,
                DEMO_EMPLOYEE_ID, null))
                .isInstanceOf(OrderNotFoundException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM active_order_claim WHERE order_id = UUID_TO_BIN(?)",
                Integer.class, expiringOrderId.toString())).isZero();

        createOrderFixture(collectedOrderId, secondDemoEmployeeId, DEMO_NORTH_THALI_COUNTER_ID, pickupSlotId,
                "03ABCDEFGH", true);
        OrderRecord preparing = orderLifecycle.transition(collectedOrderId, 1, OrderStatus.PREPARING,
                DEMO_STAFF_NORTH_THALI_ID, null);
        assertThat(preparing.status()).isEqualTo(OrderStatus.PREPARING);
        OrderRecord ready = orderLifecycle.transition(collectedOrderId, 2, OrderStatus.READY,
                DEMO_STAFF_NORTH_THALI_ID, null);
        assertThat(ready.status()).isEqualTo(OrderStatus.READY);
        OrderRecord collected = orderLifecycle.transition(collectedOrderId, 3, OrderStatus.COLLECTED,
                DEMO_STAFF_NORTH_THALI_ID, null);
        assertThat(collected.status()).isEqualTo(OrderStatus.COLLECTED);
        assertThat(collected.preparingAt()).isEqualTo(now);
        assertThat(collected.readyAt()).isEqualTo(now);
        assertThat(collected.collectedAt()).isEqualTo(now);
        assertThat(orderRepository.lockPickupToken(collectedOrderId).orElseThrow().redeemedAt()).isEqualTo(now);
        assertThat(orderRepository.lockPickupToken(collectedOrderId).orElseThrow().invalidatedAt()).isNull();
        assertThat(orderRepository.findStatusEvents(collectedOrderId)).hasSize(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM active_order_claim WHERE order_id = UUID_TO_BIN(?)",
                Integer.class, collectedOrderId.toString())).isZero();
        assertThatThrownBy(() -> orderLifecycle.transition(collectedOrderId, 4, OrderStatus.COLLECTED,
                DEMO_STAFF_NORTH_THALI_ID, null))
                .isInstanceOf(InvalidOrderTransitionException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM active_order_claim WHERE order_id = UUID_TO_BIN(?)",
                Integer.class, collectedOrderId.toString())).isZero();
    }

    @Test
    @Order(8)
    void orderConfirmationIsIdempotentAndAllowsOneActiveOrderPerCounter() throws Exception {
        insertUser(EXTRA_DEMO_EMPLOYEE_ID, "employee.demo.04", UserRole.EMPLOYEE);

        HttpClient staff = loginStaff("staff.independent.01");
        String staffCsrf = csrfToken(get(staff, "/api/v1/auth/csrf"));
        assertThat(put(staff, "/api/v1/staff/counters/" + DEMO_NORTH_DOSA_COUNTER_ID + "/opening-hours",
                fullDayHours(), staffCsrf).statusCode()).isEqualTo(200);
        assertThat(put(staff, "/api/v1/staff/counters/" + DEMO_NORTH_DOSA_COUNTER_ID + "/capacity-policy",
                capacityPolicy(5, 1, 0, 120, 60, 300), staffCsrf).statusCode()).isEqualTo(200);
        assertThat(put(staff, "/api/v1/staff/counters/" + DEMO_NORTH_DOSA_COUNTER_ID + "/availability",
                "{\"availability\":\"AVAILABLE\"}", staffCsrf).statusCode()).isEqualTo(200);
        assertThat(put(staff, "/api/v1/staff/counters/" + DEMO_NORTH_DOSA_COUNTER_ID + "/ordering",
                "{\"acceptingOnlineOrders\":true}", staffCsrf).statusCode()).isEqualTo(200);

        HttpClient wrapsStaff = loginStaff("staff.north.wraps");
        String wrapsStaffCsrf = csrfToken(get(wrapsStaff, "/api/v1/auth/csrf"));
        assertThat(put(wrapsStaff, "/api/v1/staff/counters/" + DEMO_NORTH_WRAPS_COUNTER_ID + "/opening-hours",
                fullDayHours(), wrapsStaffCsrf).statusCode()).isEqualTo(200);
        assertThat(put(wrapsStaff, "/api/v1/staff/counters/" + DEMO_NORTH_WRAPS_COUNTER_ID + "/capacity-policy",
                capacityPolicy(5, 2, 0, 120, 60, 300), wrapsStaffCsrf).statusCode()).isEqualTo(200);
        assertThat(put(wrapsStaff, "/api/v1/staff/counters/" + DEMO_NORTH_WRAPS_COUNTER_ID + "/availability",
                "{\"availability\":\"AVAILABLE\"}", wrapsStaffCsrf).statusCode()).isEqualTo(200);
        assertThat(put(wrapsStaff, "/api/v1/staff/counters/" + DEMO_NORTH_WRAPS_COUNTER_ID + "/ordering",
                "{\"acceptingOnlineOrders\":true}", wrapsStaffCsrf).statusCode()).isEqualTo(200);

        UUID concurrentEmployeeId = UUID.randomUUID();
        insertUser(concurrentEmployeeId, "employee.concurrent.samecounter", UserRole.EMPLOYEE);
        jdbc.update("UPDATE app_user SET password_hash = ? WHERE id = UUID_TO_BIN(?)",
                passwordEncoder.encode("integration-test-only-password"), concurrentEmployeeId.toString());
        HttpClient concurrentEmployee = loginEmployee("employee.concurrent.samecounter");
        String concurrentEmployeeCsrf = csrfToken(get(concurrentEmployee, "/api/v1/auth/csrf"));
        UUID sameEmployeeSlot = recommendedWindowId(get(concurrentEmployee,
                "/api/v1/counters/" + DEMO_NORTH_WRAPS_COUNTER_ID + "/order-options"));
        int sameEmployeeAllocationBefore = allocationCount(sameEmployeeSlot);
        String sameEmployeeBody = createOrderBody(DEMO_NORTH_WRAPS_COUNTER_ID, sameEmployeeSlot);
        CompletableFuture<HttpResponse<String>> sameEmployeeRequestOne = postOrderAsync(concurrentEmployee,
                sameEmployeeBody, concurrentEmployeeCsrf, UUID.randomUUID());
        CompletableFuture<HttpResponse<String>> sameEmployeeRequestTwo = postOrderAsync(concurrentEmployee,
                sameEmployeeBody, concurrentEmployeeCsrf, UUID.randomUUID());
        List<HttpResponse<String>> sameEmployeeResponses = List.of(
                sameEmployeeRequestOne.join(), sameEmployeeRequestTwo.join());
        assertThat(sameEmployeeResponses.stream().filter(response -> response.statusCode() == 201)).hasSize(1);
        HttpResponse<String> sameEmployeeConflict = sameEmployeeResponses.stream()
                .filter(response -> response.statusCode() == 409).findFirst().orElseThrow();
        assertThat(sameEmployeeConflict.body()).contains("\"code\":\"ACTIVE_ORDER_EXISTS\"");
        UUID sameEmployeeOrderId = responseId(sameEmployeeResponses.stream()
                .filter(response -> response.statusCode() == 201).findFirst().orElseThrow());
        assertThat(allocationCount(sameEmployeeSlot)).isEqualTo(sameEmployeeAllocationBefore + 1);
        orderLifecycle.transition(sameEmployeeOrderId, 1, OrderStatus.EXPIRED,
                DEMO_STAFF_NORTH_THALI_ID, "Same-employee concurrent admission test cleanup");

        HttpClient employee = loginEmployee("employee.demo.04");
        String employeeCsrf = csrfToken(get(employee, "/api/v1/auth/csrf"));
        HttpResponse<String> quote = get(employee,
                "/api/v1/counters/" + DEMO_NORTH_DOSA_COUNTER_ID + "/order-options");
        UUID selectedSlot = recommendedWindowId(quote);
        String body = createOrderBody(DEMO_NORTH_DOSA_COUNTER_ID, selectedSlot);
        UUID key = UUID.randomUUID();

        HttpResponse<String> unsupportedQuantity = postOrder(employee,
                body.substring(0, body.length() - 1) + ",\"quantity\":10}", employeeCsrf, key);
        assertThat(unsupportedQuantity.statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM idempotency_record
                WHERE employee_id = UUID_TO_BIN(?) AND idempotency_key = UUID_TO_BIN(?)
                """, Integer.class, EXTRA_DEMO_EMPLOYEE_ID.toString(), key.toString())).isZero();

        int allocationBefore = allocationCount(selectedSlot);
        List<CompletableFuture<HttpResponse<String>>> requests = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            requests.add(postOrderAsync(employee, body, employeeCsrf, key));
        }
        List<HttpResponse<String>> responses = requests.stream().map(CompletableFuture::join).toList();
        assertThat(responses.stream().filter(response -> response.statusCode() == 201)).hasSize(1);
        assertThat(responses.stream().filter(response -> response.statusCode() == 200)).hasSize(7);
        HttpResponse<String> created = responses.stream().filter(response -> response.statusCode() == 201)
                .findFirst().orElseThrow();
        UUID orderId = responseId(created);
        String pickupCode = pickupCode(created);
        assertThat(responses).allSatisfy(response -> {
            assertThat(responseId(response)).isEqualTo(orderId);
            assertThat(pickupCode(response)).isEqualTo(pickupCode);
            assertThat(response.body()).contains("\"status\":\"ACCEPTED\"", "\"version\":1",
                    "\"simulatedPaymentStatus\":\"SIMULATED_SUCCEEDED\"");
            assertThat(response.headers().firstValue("Idempotency-Replayed"))
                    .hasValue(response.statusCode() == 201 ? "false" : "true");
            assertThat(response.headers().firstValue("ETag")).hasValue("\"1\"");
        });
        assertThat(responses.stream().filter(response -> response.statusCode() == 201).findFirst().orElseThrow()
                .headers().firstValue("Location")).hasValue("/api/v1/me/orders/" + orderId);
        assertThat(allocationCount(selectedSlot)).isEqualTo(allocationBefore + 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pickup_token WHERE order_id = UUID_TO_BIN(?)",
                Integer.class, orderId.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM active_order_claim WHERE order_id = UUID_TO_BIN(?)",
                Integer.class, orderId.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_status_event WHERE order_id = UUID_TO_BIN(?)",
                Integer.class, orderId.toString())).isEqualTo(1);

        UUID anotherSlot = recommendedWindowId(get(employee,
                "/api/v1/counters/" + DEMO_NORTH_DOSA_COUNTER_ID + "/order-options"));
        HttpResponse<String> activeConflict = postOrder(employee,
                createOrderBody(DEMO_NORTH_DOSA_COUNTER_ID, anotherSlot), employeeCsrf, UUID.randomUUID());
        assertThat(activeConflict.statusCode()).isEqualTo(409);
        assertThat(activeConflict.body()).contains("\"code\":\"ACTIVE_ORDER_EXISTS\"");
        HttpResponse<String> mismatchedReplay = postOrder(employee,
                createOrderBody(DEMO_NORTH_DOSA_COUNTER_ID, UUID.randomUUID()), employeeCsrf, key);
        assertThat(mismatchedReplay.statusCode()).isEqualTo(409);
        assertThat(mismatchedReplay.body()).contains("\"code\":\"IDEMPOTENCY_KEY_REUSED\"");

        UUID wrapsSlot = recommendedWindowId(get(employee,
                "/api/v1/counters/" + DEMO_NORTH_WRAPS_COUNTER_ID + "/order-options"));
        UUID thaliSlot = recommendedWindowId(get(employee,
                "/api/v1/counters/" + DEMO_NORTH_THALI_COUNTER_ID + "/order-options"));
        CompletableFuture<HttpResponse<String>> wrapsOrder = postOrderAsync(employee,
                createOrderBody(DEMO_NORTH_WRAPS_COUNTER_ID, wrapsSlot), employeeCsrf, UUID.randomUUID());
        CompletableFuture<HttpResponse<String>> thaliOrder = postOrderAsync(employee,
                createOrderBody(DEMO_NORTH_THALI_COUNTER_ID, thaliSlot), employeeCsrf, UUID.randomUUID());
        assertThat(wrapsOrder.join().statusCode()).isEqualTo(201);
        assertThat(thaliOrder.join().statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM active_order_claim
                WHERE employee_id = UUID_TO_BIN(?) AND counter_id IN (
                    UUID_TO_BIN(?), UUID_TO_BIN(?), UUID_TO_BIN(?))
                """, Integer.class, EXTRA_DEMO_EMPLOYEE_ID.toString(),
                DEMO_NORTH_DOSA_COUNTER_ID.toString(), DEMO_NORTH_WRAPS_COUNTER_ID.toString(),
                DEMO_NORTH_THALI_COUNTER_ID.toString())).isEqualTo(3);

        orderLifecycle.transition(orderId, 1, OrderStatus.EXPIRED,
                UUID.fromString("00000000-0000-4000-8000-000000000414"), "Post-confirmation replay test");
        HttpResponse<String> replayAfterExpiry = postOrder(employee, body, employeeCsrf, key);
        assertThat(replayAfterExpiry.statusCode()).isEqualTo(200);
        assertThat(replayAfterExpiry.headers().firstValue("Idempotency-Replayed")).hasValue("true");
        assertThat(replayAfterExpiry.body()).contains("\"status\":\"ACCEPTED\"", "\"version\":1",
                "\"redeemable\":true");
        assertThat(pickupCode(replayAfterExpiry)).isEqualTo(pickupCode);
    }

    @Test
    @Order(9)
    void orderConfirmationSerializesLastSlotSoldOutAndAtomicRollback() throws Exception {
        HttpClient employeeOne = loginEmployee("employee.demo.01");
        HttpClient employeeTwo = loginEmployee("employee.demo.02");
        HttpClient employeeThree = loginEmployee("employee.demo.03");
        List<HttpClient> contenders = new ArrayList<>(List.of(employeeOne, employeeTwo, employeeThree));
        List<String> csrf = new ArrayList<>();
        for (HttpClient client : contenders) {
            csrf.add(csrfToken(get(client, "/api/v1/auth/csrf")));
        }
        String loadTestPasswordHash = passwordEncoder.encode("integration-test-only-password");
        for (int index = 4; index <= 24; index++) {
            UUID employeeId = UUID.randomUUID();
            String username = "employee.load.%02d".formatted(index);
            jdbc.update("INSERT INTO app_user (id, username, password_hash) VALUES (UUID_TO_BIN(?), ?, ?)",
                    employeeId.toString(), username, loadTestPasswordHash);
            jdbc.update("INSERT INTO user_role (user_id, role) VALUES (UUID_TO_BIN(?), ?)",
                    employeeId.toString(), UserRole.EMPLOYEE.name());
            HttpClient contender = loginEmployee(username);
            contenders.add(contender);
            csrf.add(csrfToken(get(contender, "/api/v1/auth/csrf")));
        }
        UUID contestedSlot = recommendedWindowId(get(employeeThree,
                "/api/v1/counters/" + DEMO_NORTH_DOSA_COUNTER_ID + "/order-options"));
        String contestedBody = createOrderBody(DEMO_NORTH_DOSA_COUNTER_ID, contestedSlot);
        List<CompletableFuture<HttpResponse<String>>> requests = new ArrayList<>();
        for (int index = 0; index < contenders.size(); index++) {
            requests.add(postOrderAsync(contenders.get(index), contestedBody, csrf.get(index), UUID.randomUUID()));
        }
        List<HttpResponse<String>> responses = requests.stream().map(CompletableFuture::join).toList();
        assertThat(responses.stream().filter(response -> response.statusCode() == 201)).hasSize(1);
        assertThat(responses.stream().filter(response -> response.statusCode() == 409)).hasSize(23);
        assertThat(responses.stream().filter(response -> response.statusCode() == 409)).allSatisfy(response ->
                assertThat(response.body()).contains("\"code\":\"SLOT_UNAVAILABLE\"",
                        "\"refreshedOrderOptions\""));
        assertThat(allocationCount(contestedSlot)).isEqualTo(1);

        HttpClient riceStaff = loginStaff("staff.south.rice");
        String staffCsrf = csrfToken(get(riceStaff, "/api/v1/auth/csrf"));
        assertThat(put(riceStaff, "/api/v1/staff/counters/" + DEMO_SOUTH_LEMON_RICE_COUNTER_ID + "/ordering",
                "{\"acceptingOnlineOrders\":true}", staffCsrf).statusCode()).isEqualTo(200);
        String optionsPath = "/api/v1/counters/" + DEMO_SOUTH_LEMON_RICE_COUNTER_ID + "/order-options";
        HttpClient employee = employeeTwo;
        String employeeCsrf = csrf.get(1);
        UUID riceSlot = recommendedWindowId(get(employee, optionsPath));
        int riceAllocationBeforeRace = allocationCount(riceSlot);
        CompletableFuture<HttpResponse<String>> racingOrder = postOrderAsync(employee,
                createOrderBody(DEMO_SOUTH_LEMON_RICE_COUNTER_ID, riceSlot), employeeCsrf, UUID.randomUUID());
        CompletableFuture<HttpResponse<String>> racingAvailabilityUpdate = putAsync(riceStaff,
                "/api/v1/staff/counters/" + DEMO_SOUTH_LEMON_RICE_COUNTER_ID + "/availability",
                "{\"availability\":\"SOLD_OUT\"}", staffCsrf);
        HttpResponse<String> raceResult = racingOrder.join();
        assertThat(racingAvailabilityUpdate.join().statusCode()).isEqualTo(200);
        assertThat(raceResult.statusCode()).isIn(201, 409);
        if (raceResult.statusCode() == 201) {
            assertThat(raceResult.body()).contains("\"status\":\"ACCEPTED\"");
            assertThat(allocationCount(riceSlot)).isEqualTo(riceAllocationBeforeRace + 1);
        } else {
            assertThat(raceResult.body()).contains("\"code\":\"ITEM_SOLD_OUT\"");
            assertThat(allocationCount(riceSlot)).isEqualTo(riceAllocationBeforeRace);
        }
        assertThat(put(riceStaff,
                "/api/v1/staff/counters/" + DEMO_SOUTH_LEMON_RICE_COUNTER_ID + "/availability",
                "{\"availability\":\"SOLD_OUT\"}", staffCsrf).statusCode()).isEqualTo(200);
        HttpResponse<String> soldOutConflict = postOrder(employeeThree,
                createOrderBody(DEMO_SOUTH_LEMON_RICE_COUNTER_ID, riceSlot), csrf.get(2), UUID.randomUUID());
        assertThat(soldOutConflict.statusCode()).isEqualTo(409);
        assertThat(soldOutConflict.body()).contains("\"code\":\"ITEM_SOLD_OUT\"");
        assertThat(put(riceStaff,
                "/api/v1/staff/counters/" + DEMO_SOUTH_LEMON_RICE_COUNTER_ID + "/availability",
                "{\"availability\":\"AVAILABLE\"}", staffCsrf).statusCode()).isEqualTo(200);
        assertThat(put(riceStaff,
                "/api/v1/staff/counters/" + DEMO_SOUTH_LEMON_RICE_COUNTER_ID + "/ordering",
                "{\"acceptingOnlineOrders\":false}", staffCsrf).statusCode()).isEqualTo(200);
        HttpResponse<String> pausedConflict = postOrder(employeeThree,
                createOrderBody(DEMO_SOUTH_LEMON_RICE_COUNTER_ID, riceSlot), csrf.get(2), UUID.randomUUID());
        assertThat(pausedConflict.statusCode()).isEqualTo(409);
        assertThat(pausedConflict.body()).contains("\"code\":\"ORDERING_PAUSED\"");
        assertThat(put(riceStaff,
                "/api/v1/staff/counters/" + DEMO_SOUTH_LEMON_RICE_COUNTER_ID + "/ordering",
                "{\"acceptingOnlineOrders\":true}", staffCsrf).statusCode()).isEqualTo(200);

        HttpResponse<String> staleQuote = get(employeeThree, optionsPath);
        UUID staleSlot = recommendedWindowId(staleQuote);
        jdbc.update("UPDATE pickup_slot SET confirmed_allocation_count = online_capacity_snapshot "
                + "WHERE id = UUID_TO_BIN(?)", staleSlot.toString());
        HttpResponse<String> stale = postOrder(employeeThree,
                createOrderBody(DEMO_SOUTH_LEMON_RICE_COUNTER_ID, staleSlot), csrf.get(2), UUID.randomUUID());
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(stale.body()).contains("\"code\":\"SLOT_UNAVAILABLE\"", "\"refreshedOrderOptions\"");

        HttpResponse<String> rollbackQuote = get(employeeThree, optionsPath);
        UUID rollbackSlot = recommendedWindowId(rollbackQuote);
        UUID rollbackKey = UUID.randomUUID();
        int allocationBeforeFailure = allocationCount(rollbackSlot);
        String rollbackBody = createOrderBody(DEMO_SOUTH_LEMON_RICE_COUNTER_ID, rollbackSlot);
        jdbc.execute("CREATE TRIGGER fail_order_idempotency_insert BEFORE INSERT ON idempotency_record "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced rollback test'");
        HttpResponse<String> failed;
        try {
            failed = postOrder(employeeThree, rollbackBody, csrf.get(2), rollbackKey);
        } finally {
            jdbc.execute("DROP TRIGGER fail_order_idempotency_insert");
        }
        assertThat(failed.statusCode()).isEqualTo(500);
        UUID employeeThreeId = UUID.fromString("00000000-0000-4000-8000-000000000403");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM idempotency_record
                WHERE employee_id = UUID_TO_BIN(?) AND idempotency_key = UUID_TO_BIN(?)
                """, Integer.class, employeeThreeId.toString(), rollbackKey.toString())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM meal_order
                WHERE employee_id = UUID_TO_BIN(?) AND counter_id = UUID_TO_BIN(?) AND pickup_slot_id = UUID_TO_BIN(?)
                """, Integer.class, employeeThreeId.toString(), DEMO_SOUTH_LEMON_RICE_COUNTER_ID.toString(),
                rollbackSlot.toString())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM pickup_token t JOIN meal_order o ON o.id = t.order_id
                WHERE o.employee_id = UUID_TO_BIN(?) AND o.counter_id = UUID_TO_BIN(?) AND o.pickup_slot_id = UUID_TO_BIN(?)
                """, Integer.class, employeeThreeId.toString(), DEMO_SOUTH_LEMON_RICE_COUNTER_ID.toString(),
                rollbackSlot.toString())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM order_status_event e JOIN meal_order o ON o.id = e.order_id
                WHERE o.employee_id = UUID_TO_BIN(?) AND o.counter_id = UUID_TO_BIN(?) AND o.pickup_slot_id = UUID_TO_BIN(?)
                """, Integer.class, employeeThreeId.toString(), DEMO_SOUTH_LEMON_RICE_COUNTER_ID.toString(),
                rollbackSlot.toString())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM active_order_claim c JOIN meal_order o ON o.id = c.order_id
                WHERE o.employee_id = UUID_TO_BIN(?) AND o.counter_id = UUID_TO_BIN(?) AND o.pickup_slot_id = UUID_TO_BIN(?)
                """, Integer.class, employeeThreeId.toString(), DEMO_SOUTH_LEMON_RICE_COUNTER_ID.toString(),
                rollbackSlot.toString())).isZero();
        assertThat(allocationCount(rollbackSlot)).isEqualTo(allocationBeforeFailure);
        HttpResponse<String> retryAfterRollback = postOrder(employeeThree, rollbackBody, csrf.get(2), rollbackKey);
        assertThat(retryAfterRollback.statusCode()).isEqualTo(201);
        assertThat(allocationCount(rollbackSlot)).isEqualTo(allocationBeforeFailure + 1);
    }

    @Test
    @Order(10)
    void employeeOrderReadsAreOwnerScopedFilteredPagedAndReflectAllocations() throws Exception {
        HttpClient employee = loginEmployee("employee.demo.04");
        String ordersPath = "/api/v1/me/orders";
        UUID wrapsOrderId = ownedOrderId(EXTRA_DEMO_EMPLOYEE_ID, DEMO_NORTH_WRAPS_COUNTER_ID);
        UUID thaliOrderId = ownedOrderId(EXTRA_DEMO_EMPLOYEE_ID, DEMO_NORTH_THALI_COUNTER_ID);
        UUID expiredDosaOrderId = jdbc.queryForObject("""
                SELECT BIN_TO_UUID(id) FROM meal_order
                WHERE employee_id = UUID_TO_BIN(?) AND counter_id = UUID_TO_BIN(?) AND status = 'EXPIRED'
                """, (result, row) -> UUID.fromString(result.getString(1)),
                EXTRA_DEMO_EMPLOYEE_ID.toString(), DEMO_NORTH_DOSA_COUNTER_ID.toString());

        HttpResponse<String> active = get(employee, ordersPath);
        assertThat(active.statusCode()).isEqualTo(200);
        assertThat(active.body()).contains("\"totalItems\":2", "\"totalPages\":1",
                DEMO_NORTH_WRAPS_COUNTER_ID.toString(), DEMO_NORTH_THALI_COUNTER_ID.toString());
        assertThat(active.body()).doesNotContain("pickupToken", "\"code\"");

        HttpResponse<String> firstPage = get(employee, ordersPath + "?page=0&size=1");
        HttpResponse<String> secondPage = get(employee, ordersPath + "?page=1&size=1");
        assertThat(firstPage.body()).contains("\"page\":0", "\"size\":1", "\"totalPages\":2");
        assertThat(secondPage.body()).contains("\"page\":1", "\"size\":1", "\"totalPages\":2");
        assertThat(responseId(firstPage)).isNotEqualTo(responseId(secondPage));
        assertThat(Set.of(wrapsOrderId, thaliOrderId)).contains(responseId(firstPage), responseId(secondPage));
        assertThat(get(employee, ordersPath + "?size=101").statusCode()).isEqualTo(400);

        HttpResponse<String> history = get(employee, ordersPath + "?status=EXPIRED");
        assertThat(history.statusCode()).isEqualTo(200);
        assertThat(history.body()).contains("\"totalItems\":1", expiredDosaOrderId.toString());
        assertThat(history.body()).doesNotContain("pickupToken", "\"code\"");

        java.sql.Timestamp collectedAt = java.sql.Timestamp.from(clock.instant());
        jdbc.update("UPDATE meal_order SET status = 'COLLECTED', version = version + 1, preparing_at = ?, "
                + "ready_at = ?, collected_at = ? WHERE id = UUID_TO_BIN(?)",
                collectedAt, collectedAt, collectedAt, wrapsOrderId.toString());
        jdbc.update("UPDATE pickup_token SET redeemed_at = ? WHERE order_id = UUID_TO_BIN(?)",
                collectedAt, wrapsOrderId.toString());
        jdbc.update("DELETE FROM active_order_claim WHERE order_id = UUID_TO_BIN(?)", wrapsOrderId.toString());
        HttpResponse<String> collectedHistory = get(employee, ordersPath + "?status=COLLECTED&status=EXPIRED");
        assertThat(collectedHistory.statusCode()).isEqualTo(200);
        assertThat(collectedHistory.body()).contains("\"totalItems\":2", wrapsOrderId.toString(),
                expiredDosaOrderId.toString());

        HttpResponse<String> expiredDetail = get(employee, ordersPath + "/" + expiredDosaOrderId);
        assertThat(expiredDetail.statusCode()).isEqualTo(200);
        assertThat(expiredDetail.headers().firstValue("ETag")).hasValue("\"2\"");
        assertThat(expiredDetail.body()).contains("\"status\":\"EXPIRED\"", "\"redeemable\":false");
        assertThat(expiredDetail.body()).contains(pickupCodeFromDatabase(expiredDosaOrderId));

        HttpResponse<String> collectedDetail = get(employee, ordersPath + "/" + wrapsOrderId);
        assertThat(collectedDetail.statusCode()).isEqualTo(200);
        assertThat(collectedDetail.body()).contains("\"status\":\"COLLECTED\"", "\"redeemable\":false");

        HttpClient anotherEmployee = loginEmployee("employee.demo.01");
        HttpResponse<String> concealedOrder = get(anotherEmployee, ordersPath + "/" + expiredDosaOrderId);
        assertThat(concealedOrder.statusCode()).isEqualTo(404);
        assertThat(concealedOrder.body()).doesNotContain(pickupCodeFromDatabase(expiredDosaOrderId));

        jdbc.update("UPDATE meal_order SET pickup_window_start_at = ?, pickup_window_end_at = ? WHERE id = UUID_TO_BIN(?)",
                java.sql.Timestamp.from(clock.instant().minusSeconds(11)),
                java.sql.Timestamp.from(clock.instant().minusSeconds(1)), thaliOrderId.toString());
        HttpResponse<String> overdueDetail = get(employee, ordersPath + "/" + thaliOrderId);
        assertThat(overdueDetail.statusCode()).isEqualTo(200);
        assertThat(overdueDetail.body()).contains("\"status\":\"ACCEPTED\"", "\"overdue\":true",
                "\"redeemable\":true");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM active_order_claim WHERE order_id = UUID_TO_BIN(?)",
                Integer.class, thaliOrderId.toString())).isEqualTo(1);

        UUID thaliSlotId = jdbc.queryForObject("SELECT BIN_TO_UUID(pickup_slot_id) FROM meal_order WHERE id = UUID_TO_BIN(?)",
                (result, row) -> UUID.fromString(result.getString(1)), thaliOrderId.toString());
        int allocations = allocationCount(thaliSlotId);
        int onlineCapacity = jdbc.queryForObject("SELECT online_capacity_snapshot FROM pickup_slot WHERE id = UUID_TO_BIN(?)",
                Integer.class, thaliSlotId.toString());
        HttpResponse<String> refreshedOptions = get(employee,
                "/api/v1/counters/" + DEMO_NORTH_THALI_COUNTER_ID + "/order-options");
        assertThat(refreshedOptions.statusCode()).isEqualTo(200);
        java.util.regex.Pattern allocatedWindow = java.util.regex.Pattern.compile(
                "(?s)" + java.util.regex.Pattern.quote("\"id\":\"" + thaliSlotId + "\"")
                        + ".*?\"allocated\":(\\d+),\"remaining\":(\\d+)");
        java.util.regex.Matcher allocation = allocatedWindow.matcher(refreshedOptions.body());
        assertThat(allocation.find()).isTrue();
        assertThat(Integer.parseInt(allocation.group(1))).isEqualTo(allocations);
        assertThat(Integer.parseInt(allocation.group(2))).isEqualTo(onlineCapacity - allocations);
    }

    @Test
    @Order(11)
    void assignedStaffCanFilterQueuesAndSerializeVersionedFulfillmentActions() throws Exception {
        HttpClient employee = loginEmployee("employee.demo.04");
        String employeeCsrf = csrfToken(get(employee, "/api/v1/auth/csrf"));
        UUID slotId = recommendedWindowId(get(employee,
                "/api/v1/counters/" + DEMO_NORTH_DOSA_COUNTER_ID + "/order-options"));
        UUID idempotencyKey = UUID.randomUUID();
        String orderBody = createOrderBody(DEMO_NORTH_DOSA_COUNTER_ID, slotId);
        HttpResponse<String> created = postOrder(employee, orderBody, employeeCsrf, idempotencyKey);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).contains("\"estimatedReadyAt\":\"",
                "\"expectedOnsiteWaitMinutes\":null",
                "\"onsiteWaitUnavailableReason\":\"WALK_IN_AND_HANDOVER_TIMES_NOT_MEASURED\"");
        UUID orderId = responseId(created);
        String originalPickupCode = pickupCode(created);
        int allocationBeforeExpiry = allocationCount(slotId);

        HttpClient staffOne = loginStaff("staff.independent.01");
        HttpClient staffTwo = loginStaff("staff.independent.02");
        HttpClient unassignedStaff = loginStaff("staff.north.thali");
        String staffOneCsrf = csrfToken(get(staffOne, "/api/v1/auth/csrf"));
        String staffTwoCsrf = csrfToken(get(staffTwo, "/api/v1/auth/csrf"));
        String unassignedCsrf = csrfToken(get(unassignedStaff, "/api/v1/auth/csrf"));

        HttpResponse<String> workspace = get(staffOne, "/api/v1/staff/counters");
        assertThat(workspace.statusCode()).isEqualTo(200);
        assertThat(workspace.body()).contains(DEMO_NORTH_DOSA_COUNTER_ID.toString(),
                DEMO_SOUTH_IDLI_COUNTER_ID.toString());
        assertThat(workspace.body()).doesNotContain(DEMO_NORTH_THALI_COUNTER_ID.toString());

        HttpResponse<String> capacityWithWorkload = get(staffOne,
                "/api/v1/staff/counters/" + DEMO_NORTH_DOSA_COUNTER_ID + "/capacity");
        assertThat(capacityWithWorkload.statusCode()).isEqualTo(200);
        assertThat(capacityWithWorkload.body()).contains("\"unreadyEarlierOrders\":1",
                "\"estimatedLatestReadyAt\":\"");

        String queuePath = "/api/v1/staff/counters/" + DEMO_NORTH_DOSA_COUNTER_ID + "/orders";
        assertThat(get(unassignedStaff, queuePath).statusCode()).isEqualTo(403);
        assertThat(postTransition(unassignedStaff, "/api/v1/staff/orders/" + orderId + "/preparing",
                "", unassignedCsrf, "\"1\"").statusCode()).isEqualTo(403);

        HttpResponse<String> queue = get(staffOne, queuePath);
        assertThat(queue.statusCode()).isEqualTo(200);
        assertThat(queue.body()).contains("\"totalItems\":2", orderId.toString(),
                "\"employeeDisplayLabel\":\"employee.demo.04\"");
        List<Instant> pickupStarts = pickupWindowStarts(queue);
        assertThat(pickupStarts).hasSize(2).isSorted();
        assertThat(get(staffOne, queuePath + "?status=EXPIRED").body())
                .contains("\"totalItems\":1", "\"status\":\"EXPIRED\"");
        Instant slotStart = jdbc.queryForObject("SELECT start_at FROM pickup_slot WHERE id = UUID_TO_BIN(?)",
                (result, row) -> result.getTimestamp(1).toInstant(), slotId.toString());
        String encodedStart = URLEncoder.encode(slotStart.toString(), StandardCharsets.UTF_8);
        HttpResponse<String> pickupFiltered = get(staffOne, queuePath + "?pickupWindowStart=" + encodedStart);
        assertThat(pickupFiltered.statusCode()).isEqualTo(200);
        assertThat(pickupFiltered.body()).contains("\"totalItems\":1", orderId.toString());

        jdbc.update("UPDATE meal_order SET pickup_window_start_at = ?, pickup_window_end_at = ? "
                        + "WHERE id = UUID_TO_BIN(?)",
                java.sql.Timestamp.from(clock.instant().minusSeconds(11)),
                java.sql.Timestamp.from(clock.instant().minusSeconds(1)), orderId.toString());
        HttpResponse<String> overdueQueue = get(staffOne, queuePath + "?overdue=true");
        assertThat(overdueQueue.statusCode()).isEqualTo(200);
        assertThat(overdueQueue.body()).contains("\"totalItems\":1", orderId.toString(), "\"overdue\":true");
        assertThat(workspace.body()).contains("\"queueCounts\":{\"accepted\":2,\"preparing\":0,"
                + "\"ready\":0,\"due\":0,\"overdue\":0}");

        jdbc.update("UPDATE meal_order SET pickup_window_start_at = ?, pickup_window_end_at = ? "
                        + "WHERE id = UUID_TO_BIN(?)",
                java.sql.Timestamp.from(clock.instant().minusSeconds(60)),
                java.sql.Timestamp.from(clock.instant().plusSeconds(60)), orderId.toString());
        HttpResponse<String> dueWorkspace = get(staffOne, "/api/v1/staff/counters");
        assertThat(dueWorkspace.statusCode()).isEqualTo(200);
        assertThat(dueWorkspace.body()).contains("\"due\":1");
        jdbc.update("UPDATE meal_order SET pickup_window_end_at = ? WHERE id = UUID_TO_BIN(?)",
                java.sql.Timestamp.from(clock.instant().minusSeconds(1)), orderId.toString());

        assertThat(put(staffOne, "/api/v1/staff/counters/" + DEMO_NORTH_DOSA_COUNTER_ID + "/availability",
                "{\"availability\":\"SOLD_OUT\"}", staffOneCsrf).statusCode()).isEqualTo(200);
        assertThat(put(staffOne, "/api/v1/staff/counters/" + DEMO_NORTH_DOSA_COUNTER_ID + "/ordering",
                "{\"acceptingOnlineOrders\":false}", staffOneCsrf).statusCode()).isEqualTo(200);

        String orderPath = "/api/v1/staff/orders/" + orderId;
        HttpResponse<String> preparing = postTransition(staffOne, orderPath + "/preparing", "",
                staffOneCsrf, "\"1\"");
        assertThat(preparing.statusCode()).isEqualTo(200);
        assertThat(preparing.headers().firstValue("ETag")).hasValue("\"2\"");
        assertThat(preparing.body()).contains("\"status\":\"PREPARING\"", "\"overdue\":true");

        CompletableFuture<HttpResponse<String>> readyOne = postTransitionAsync(staffOne,
                orderPath + "/ready", "", staffOneCsrf, "\"2\"");
        CompletableFuture<HttpResponse<String>> readyTwo = postTransitionAsync(staffTwo,
                orderPath + "/ready", "", staffTwoCsrf, "\"2\"");
        List<HttpResponse<String>> readyResponses = List.of(readyOne.join(), readyTwo.join());
        assertThat(readyResponses.stream().filter(response -> response.statusCode() == 200)).hasSize(1);
        assertThat(readyResponses.stream().filter(response -> response.statusCode() == 412)).hasSize(1);
        HttpResponse<String> staleResponse = readyResponses.stream()
                .filter(response -> response.statusCode() == 412).findFirst().orElseThrow();
        assertThat(staleResponse.body()).contains("\"code\":\"PRECONDITION_FAILED\"",
                "\"currentOrder\"", "\"status\":\"READY\"", "\"version\":3");
        assertThat(readyResponses.stream().filter(response -> response.statusCode() == 200).findFirst().orElseThrow()
                .headers().firstValue("ETag")).hasValue("\"3\"");

        HttpResponse<String> reverseTransition = postTransition(staffOne, orderPath + "/preparing", "",
                staffOneCsrf, "\"3\"");
        assertThat(reverseTransition.statusCode()).isEqualTo(422);
        assertThat(reverseTransition.body()).contains("\"code\":\"INVALID_ORDER_TRANSITION\"");
        HttpResponse<String> expired = postTransition(staffOne, orderPath + "/expiry",
                "{\"reason\":\"Food ran out at the counter\"}", staffOneCsrf, "\"3\"");
        assertThat(expired.statusCode()).isEqualTo(200);
        assertThat(expired.headers().firstValue("ETag")).hasValue("\"4\"");
        assertThat(expired.body()).contains("\"status\":\"EXPIRED\"",
                "\"expiryReason\":\"Food ran out at the counter\"");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM active_order_claim WHERE order_id = UUID_TO_BIN(?)",
                Integer.class, orderId.toString())).isZero();
        assertThat(orderRepository.findPickupToken(orderId).orElseThrow().isRedeemable()).isFalse();
        assertThat(allocationCount(slotId)).isEqualTo(allocationBeforeExpiry);
        assertThat(orderRepository.findStatusEvents(orderId)).hasSize(4);

        HttpResponse<String> creationReplay = postOrder(employee, orderBody, employeeCsrf, idempotencyKey);
        assertThat(creationReplay.statusCode()).isEqualTo(200);
        assertThat(creationReplay.body()).contains("\"status\":\"ACCEPTED\"", "\"version\":1",
                "\"redeemable\":true");
        assertThat(pickupCode(creationReplay)).isEqualTo(originalPickupCode);

        assertThat(put(staffOne, "/api/v1/staff/counters/" + DEMO_NORTH_DOSA_COUNTER_ID + "/availability",
                "{\"availability\":\"AVAILABLE\"}", staffOneCsrf).statusCode()).isEqualTo(200);
        assertThat(put(staffOne, "/api/v1/staff/counters/" + DEMO_NORTH_DOSA_COUNTER_ID + "/ordering",
                "{\"acceptingOnlineOrders\":true}", staffOneCsrf).statusCode()).isEqualTo(200);
        UUID nextSlot = recommendedWindowId(get(employee,
                "/api/v1/counters/" + DEMO_NORTH_DOSA_COUNTER_ID + "/order-options"));
        HttpResponse<String> nextOrder = postOrder(employee,
                createOrderBody(DEMO_NORTH_DOSA_COUNTER_ID, nextSlot), employeeCsrf, UUID.randomUUID());
        assertThat(nextOrder.statusCode()).isEqualTo(201);
        assertThat(nextOrder.body()).contains("\"status\":\"ACCEPTED\"");
    }

    @Test
    @Order(12)
    void staffCanRedeemReadyPickupTokensOnceAndSerializeExpiryRaces() throws Exception {
        insertUser(REDEMPTION_TEST_STAFF_ID, "staff.redemption.test", UserRole.VENDOR_STAFF);
        jdbc.update("INSERT INTO staff_counter_assignment (user_id, counter_id) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?)), (UUID_TO_BIN(?), UUID_TO_BIN(?))",
                REDEMPTION_TEST_STAFF_ID.toString(), DEMO_NORTH_DOSA_COUNTER_ID.toString(),
                REDEMPTION_TEST_STAFF_ID.toString(), DEMO_SOUTH_BOWLS_COUNTER_ID.toString());
        HttpClient employee = loginEmployee("employee.demo.04");
        String employeeCsrf = csrfToken(get(employee, "/api/v1/auth/csrf"));
        HttpClient staffOne = loginStaff("staff.independent.01");
        HttpClient staffTwo = loginStaff("staff.independent.02");
        HttpClient unassignedStaff = loginStaff("staff.north.thali");
        String staffOneCsrf = csrfToken(get(staffOne, "/api/v1/auth/csrf"));
        String staffTwoCsrf = csrfToken(get(staffTwo, "/api/v1/auth/csrf"));
        String unassignedCsrf = csrfToken(get(unassignedStaff, "/api/v1/auth/csrf"));

        UUID originalOrderId = ownedOrderId(EXTRA_DEMO_EMPLOYEE_ID, DEMO_NORTH_DOSA_COUNTER_ID);
        String originalPickupCode = pickupCodeFromDatabase(originalOrderId);
        UUID originalSlotId = jdbc.queryForObject(
                "SELECT BIN_TO_UUID(pickup_slot_id) FROM meal_order WHERE id = UUID_TO_BIN(?)",
                (result, row) -> UUID.fromString(result.getString(1)), originalOrderId.toString());
        int originalAllocation = allocationCount(originalSlotId);
        UUID originalIdempotencyKey = jdbc.queryForObject("""
                SELECT BIN_TO_UUID(idempotency_key) FROM idempotency_record
                WHERE employee_id = UUID_TO_BIN(?) AND order_id = UUID_TO_BIN(?)
                """, (result, row) -> UUID.fromString(result.getString(1)),
                EXTRA_DEMO_EMPLOYEE_ID.toString(), originalOrderId.toString());
        String originalOrderBody = createOrderBody(DEMO_NORTH_DOSA_COUNTER_ID, originalSlotId);
        String dosaRedemptionPath = "/api/v1/staff/counters/" + DEMO_NORTH_DOSA_COUNTER_ID
                + "/pickup-redemptions";

        HttpResponse<String> unassignedRedemption = postPickupRedemption(unassignedStaff, dosaRedemptionPath,
                originalPickupCode, unassignedCsrf);
        assertThat(unassignedRedemption.statusCode()).isEqualTo(403);
        HttpResponse<String> wrongCounter = postPickupRedemption(staffOne,
                "/api/v1/staff/counters/" + DEMO_SOUTH_IDLI_COUNTER_ID + "/pickup-redemptions",
                originalPickupCode, staffOneCsrf);
        assertThat(wrongCounter.statusCode()).isEqualTo(422);
        assertThat(wrongCounter.body()).contains("\"code\":\"INVALID_OR_UNAVAILABLE_TOKEN\"")
                .doesNotContain(originalPickupCode);
        HttpResponse<String> notReady = postPickupRedemption(staffOne, dosaRedemptionPath,
                originalPickupCode, staffOneCsrf);
        assertThat(notReady.statusCode()).isEqualTo(422);
        assertThat(notReady.body()).contains("\"code\":\"INVALID_OR_UNAVAILABLE_TOKEN\"")
                .doesNotContain(originalPickupCode);
        HttpResponse<String> unknownCode = postPickupRedemption(staffOne, dosaRedemptionPath,
                "0000000000", staffOneCsrf);
        assertThat(unknownCode.statusCode()).isEqualTo(422);

        UUID previouslyExpiredOrder = jdbc.queryForObject("""
                SELECT BIN_TO_UUID(id) FROM meal_order
                WHERE employee_id = UUID_TO_BIN(?) AND counter_id = UUID_TO_BIN(?) AND status = 'EXPIRED'
                ORDER BY placed_at DESC LIMIT 1
                """, (result, row) -> UUID.fromString(result.getString(1)),
                EXTRA_DEMO_EMPLOYEE_ID.toString(), DEMO_NORTH_DOSA_COUNTER_ID.toString());
        HttpResponse<String> expiredToken = postPickupRedemption(staffOne, dosaRedemptionPath,
                pickupCodeFromDatabase(previouslyExpiredOrder), staffOneCsrf);
        assertThat(expiredToken.statusCode()).isEqualTo(422);

        HttpResponse<String> preparing = postTransition(staffOne,
                "/api/v1/staff/orders/" + originalOrderId + "/preparing", "", staffOneCsrf, "\"1\"");
        assertThat(preparing.statusCode()).isEqualTo(200);
        HttpResponse<String> ready = postTransition(staffOne,
                "/api/v1/staff/orders/" + originalOrderId + "/ready", "", staffOneCsrf, "\"2\"");
        assertThat(ready.statusCode()).isEqualTo(200);
        Instant originalSlotStart = jdbc.queryForObject("SELECT start_at FROM pickup_slot WHERE id = UUID_TO_BIN(?)",
                (result, row) -> result.getTimestamp(1).toInstant(), originalSlotId.toString());
        assertThat(originalSlotStart).isAfter(clock.instant());

        HttpResponse<String> collected = postPickupRedemption(staffOne, dosaRedemptionPath,
                originalPickupCode, staffOneCsrf);
        assertThat(collected.statusCode()).isEqualTo(200);
        assertThat(collected.headers().firstValue("ETag")).hasValue("\"4\"");
        assertThat(collected.body()).contains("\"status\":\"COLLECTED\"", "\"collectedAt\"")
                .doesNotContain(originalPickupCode);
        assertThat(orderRepository.findById(originalOrderId).orElseThrow().status())
                .isEqualTo(OrderStatus.COLLECTED);
        assertThat(orderRepository.findPickupToken(originalOrderId).orElseThrow().redeemedAt())
                .isEqualTo(clock.instant());
        assertThat(orderRepository.findPickupToken(originalOrderId).orElseThrow().invalidatedAt()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM active_order_claim WHERE order_id = UUID_TO_BIN(?)",
                Integer.class, originalOrderId.toString())).isZero();
        assertThat(allocationCount(originalSlotId)).isEqualTo(originalAllocation);
        assertThat(orderRepository.findStatusEvents(originalOrderId)).hasSize(4);

        HttpResponse<String> replay = postOrder(employee, originalOrderBody, employeeCsrf, originalIdempotencyKey);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).contains("\"status\":\"ACCEPTED\"", "\"version\":1");
        assertThat(pickupCode(replay)).isEqualTo(originalPickupCode);
        assertThat(postPickupRedemption(staffOne, dosaRedemptionPath, originalPickupCode, staffOneCsrf)
                .statusCode()).isEqualTo(422);

        UUID nextSlotId = recommendedWindowId(get(employee,
                "/api/v1/counters/" + DEMO_NORTH_DOSA_COUNTER_ID + "/order-options"));
        HttpResponse<String> nextOrder = postOrder(employee,
                createOrderBody(DEMO_NORTH_DOSA_COUNTER_ID, nextSlotId), employeeCsrf, UUID.randomUUID());
        assertThat(nextOrder.statusCode()).isEqualTo(201);
        UUID nextOrderId = responseId(nextOrder);
        String nextCode = pickupCode(nextOrder);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM active_order_claim WHERE order_id = UUID_TO_BIN(?)",
                Integer.class, nextOrderId.toString())).isEqualTo(1);

        HttpResponse<String> nextPreparing = postTransition(staffOne,
                "/api/v1/staff/orders/" + nextOrderId + "/preparing", "", staffOneCsrf, "\"1\"");
        assertThat(nextPreparing.statusCode()).isEqualTo(200);
        HttpResponse<String> nextReady = postTransition(staffOne,
                "/api/v1/staff/orders/" + nextOrderId + "/ready", "", staffOneCsrf, "\"2\"");
        assertThat(nextReady.statusCode()).isEqualTo(200);
        CompletableFuture<HttpResponse<String>> duplicateOne = postPickupRedemptionAsync(
                staffOne, dosaRedemptionPath, nextCode, staffOneCsrf);
        CompletableFuture<HttpResponse<String>> duplicateTwo = postPickupRedemptionAsync(
                staffTwo, dosaRedemptionPath, nextCode, staffTwoCsrf);
        List<HttpResponse<String>> duplicateRedemptions = List.of(duplicateOne.join(), duplicateTwo.join());
        assertThat(duplicateRedemptions.stream().filter(response -> response.statusCode() == 200)).hasSize(1);
        assertThat(duplicateRedemptions.stream().filter(response -> response.statusCode() == 422)).hasSize(1);
        assertThat(orderRepository.findById(nextOrderId).orElseThrow().status()).isEqualTo(OrderStatus.COLLECTED);
        assertThat(orderRepository.findStatusEvents(nextOrderId)).hasSize(4);

        UUID raceSlotId = recommendedWindowId(get(employee,
                "/api/v1/counters/" + DEMO_NORTH_DOSA_COUNTER_ID + "/order-options"));
        HttpResponse<String> raceOrder = postOrder(employee,
                createOrderBody(DEMO_NORTH_DOSA_COUNTER_ID, raceSlotId), employeeCsrf, UUID.randomUUID());
        assertThat(raceOrder.statusCode()).isEqualTo(201);
        UUID raceOrderId = responseId(raceOrder);
        String raceCode = pickupCode(raceOrder);
        assertThat(postTransition(staffOne, "/api/v1/staff/orders/" + raceOrderId + "/preparing", "",
                staffOneCsrf, "\"1\"").statusCode()).isEqualTo(200);
        assertThat(postTransition(staffOne, "/api/v1/staff/orders/" + raceOrderId + "/ready", "",
                staffOneCsrf, "\"2\"").statusCode()).isEqualTo(200);
        int raceAllocation = allocationCount(raceSlotId);
        CompletableFuture<HttpResponse<String>> redemptionRace = postPickupRedemptionAsync(
                staffOne, dosaRedemptionPath, raceCode, staffOneCsrf);
        CompletableFuture<HttpResponse<String>> expiryRace = postTransitionAsync(staffTwo,
                "/api/v1/staff/orders/" + raceOrderId + "/expiry",
                "{\"reason\":\"Counter service ended during pickup\"}", staffTwoCsrf, "\"3\"");
        HttpResponse<String> redemptionRaceResponse = redemptionRace.join();
        HttpResponse<String> expiryRaceResponse = expiryRace.join();
        assertThat(List.of(redemptionRaceResponse, expiryRaceResponse).stream()
                .filter(response -> response.statusCode() == 200)).hasSize(1);
        OrderRecord racedOrder = orderRepository.findById(raceOrderId).orElseThrow();
        assertThat(racedOrder.status()).isIn(OrderStatus.COLLECTED, OrderStatus.EXPIRED);
        assertThat(orderRepository.findStatusEvents(raceOrderId)).hasSize(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM active_order_claim WHERE order_id = UUID_TO_BIN(?)",
                Integer.class, raceOrderId.toString())).isZero();
        assertThat(allocationCount(raceSlotId)).isEqualTo(raceAllocation);
        if (racedOrder.status() == OrderStatus.COLLECTED) {
            assertThat(redemptionRaceResponse.statusCode()).isEqualTo(200);
            assertThat(expiryRaceResponse.statusCode()).isEqualTo(412);
            assertThat(orderRepository.findPickupToken(raceOrderId).orElseThrow().redeemedAt()).isNotNull();
            assertThat(orderRepository.findPickupToken(raceOrderId).orElseThrow().invalidatedAt()).isNull();
        } else {
            assertThat(expiryRaceResponse.statusCode()).isEqualTo(200);
            assertThat(redemptionRaceResponse.statusCode()).isEqualTo(422);
            assertThat(orderRepository.findPickupToken(raceOrderId).orElseThrow().redeemedAt()).isNull();
            assertThat(orderRepository.findPickupToken(raceOrderId).orElseThrow().invalidatedAt()).isNotNull();
        }

        HttpClient throttleStaff = loginStaff("staff.redemption.test");
        String throttleStaffCsrf = csrfToken(get(throttleStaff, "/api/v1/auth/csrf"));
        for (int attempt = 0; attempt < 10; attempt++) {
            HttpResponse<String> rejected = postPickupRedemption(throttleStaff, dosaRedemptionPath,
                    "0000000000", throttleStaffCsrf);
            assertThat(rejected.statusCode()).isEqualTo(422);
        }
        HttpResponse<String> limited = postPickupRedemption(throttleStaff, dosaRedemptionPath,
                "0000000000", throttleStaffCsrf);
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.body()).contains("\"code\":\"RATE_LIMITED\"");
        HttpResponse<String> otherCounterAttempt = postPickupRedemption(throttleStaff,
                "/api/v1/staff/counters/" + DEMO_SOUTH_BOWLS_COUNTER_ID + "/pickup-redemptions",
                "0000000000", throttleStaffCsrf);
        assertThat(otherCounterAttempt.statusCode()).isEqualTo(422);
    }

    @Test
    @Order(13)
    void counterReportsUseCampusDatesHistoricalSnapshotsAndAssignedCounterScope() throws Exception {
        UUID itemId = jdbc.queryForObject("SELECT BIN_TO_UUID(id) FROM menu_item WHERE counter_id = UUID_TO_BIN(?)",
                (result, row) -> UUID.fromString(result.getString(1)), DEMO_NORTH_THALI_COUNTER_ID.toString());
        UUID slotId = jdbc.queryForObject("SELECT BIN_TO_UUID(id) FROM pickup_slot WHERE counter_id = UUID_TO_BIN(?) LIMIT 1",
                (result, row) -> UUID.fromString(result.getString(1)), DEMO_NORTH_THALI_COUNTER_ID.toString());

        insertReportOrder(UUID.randomUUID(), itemId, slotId, "Historic Thali Regular", 10_000,
                OrderStatus.COLLECTED, "2025-02-01T00:10:00", "2025-02-01T00:15:00",
                "2025-02-01T00:25:00", "2025-02-01T00:45:00", "2025-02-01T00:55:00", null,
                "2025-02-01T00:50:00", "2025-02-01T01:00:00");
        insertReportOrder(UUID.randomUUID(), itemId, slotId, "Historic Thali Large", 12_500,
                OrderStatus.COLLECTED, "2025-02-01T23:30:00", "2025-02-01T23:32:00",
                "2025-02-01T23:42:00", "2025-02-01T23:52:00", "2025-02-01T23:59:00", null,
                "2025-02-01T23:50:00", "2025-02-02T00:05:00");
        insertReportOrder(UUID.randomUUID(), itemId, slotId, "Historic Thali Regular", 6_000,
                OrderStatus.EXPIRED, "2025-02-01T12:00:00", "2025-02-01T12:00:00",
                null, null, null, "2025-02-01T13:00:00", "2025-02-01T12:30:00", "2025-02-01T12:40:00");
        insertReportOrder(UUID.randomUUID(), itemId, slotId, "Boundary Thali", 7_000,
                OrderStatus.COLLECTED, "2025-02-01T23:59:00", "2025-02-01T23:59:00",
                "2025-02-02T00:00:00", "2025-02-02T00:05:00", "2025-02-02T00:06:00", null,
                "2025-02-02T00:00:00", "2025-02-02T00:10:00");

        Long originalPrice = jdbc.queryForObject("SELECT price_minor FROM menu_item WHERE id = UUID_TO_BIN(?)",
                Long.class, itemId.toString());
        String originalName = jdbc.queryForObject("SELECT name FROM menu_item WHERE id = UUID_TO_BIN(?)",
                String.class, itemId.toString());
        jdbc.update("UPDATE menu_item SET name = ?, price_minor = 99999 WHERE id = UUID_TO_BIN(?)",
                "Current Thali", itemId.toString());
        try {
            HttpClient thaliStaff = loginStaff("staff.north.thali");
            HttpClient wrapsStaff = loginStaff("staff.north.wraps");
            HttpClient employee = loginEmployee("employee.demo.01");
            String reportPath = "/api/v1/staff/counters/" + DEMO_NORTH_THALI_COUNTER_ID + "/report";
            HttpResponse<String> response = get(thaliStaff, reportPath
                    + "?startDate=2025-02-01&endDate=2025-02-01");
            assertThat(response.statusCode()).isEqualTo(200);

            String report = response.body();
            assertThat(report).contains("\"timeZone\":\"Asia/Kolkata\"", "\"startDate\":\"2025-02-01\"",
                    "\"endDate\":\"2025-02-01\"");
            assertJsonNumber(report, "ordersAccepted", 4);
            assertJsonNumber(report, "ordersCollected", 2);
            assertJsonNumber(report, "ordersCollectedOnTime", 2);
            assertJsonNumber(report, "ordersExpired", 1);
            assertThat(report).contains("\"simulatedAcceptedValue\":{\"amountMinor\":35500,\"currency\":\"INR\"}",
                    "\"simulatedCollectedValue\":{\"amountMinor\":22500,\"currency\":\"INR\"}");
            assertThat(countOccurrences(report, "\"localHour\":")).isEqualTo(24);
            assertThat(report).contains("\"localHour\":0,\"ordersPlaced\":1",
                    "\"localHour\":12,\"ordersPlaced\":1", "\"localHour\":23,\"ordersPlaced\":2",
                    "\"mealsCollectedByItem\":[{\"itemName\":\"Historic Thali Large\","
                            + "\"itemPrice\":{\"amountMinor\":12500,\"currency\":\"INR\"},\"count\":1,"
                            + "\"simulatedValue\":{\"amountMinor\":12500,\"currency\":\"INR\"}},"
                            + "{\"itemName\":\"Historic Thali Regular\","
                            + "\"itemPrice\":{\"amountMinor\":10000,\"currency\":\"INR\"},\"count\":1,"
                            + "\"simulatedValue\":{\"amountMinor\":10000,\"currency\":\"INR\"}}");
            assertThat(response.body()).doesNotContain("Current Thali", "99999");
            assertTiming(report, "acceptedToPreparing", 2, "10.00", "10.00");
            assertTiming(report, "preparingToReady", 2, "15.00", "20.00");
            assertTiming(report, "readyToCollected", 2, "8.50", "10.00");
            assertTiming(report, "placedToCollected", 2, "37.00", "45.00");

            HttpResponse<String> nextDay = get(thaliStaff, reportPath
                    + "?startDate=2025-02-02&endDate=2025-02-02");
            assertThat(nextDay.statusCode()).isEqualTo(200);
            assertJsonNumber(nextDay.body(), "ordersAccepted", 0);
            assertJsonNumber(nextDay.body(), "ordersCollected", 1);
            assertThat(nextDay.body()).contains(
                    "\"simulatedCollectedValue\":{\"amountMinor\":7000,\"currency\":\"INR\"}");

            HttpResponse<String> empty = get(thaliStaff, reportPath
                    + "?startDate=2025-02-03&endDate=2025-02-03");
            assertThat(empty.statusCode()).isEqualTo(200);
            assertTiming(empty.body(), "placedToCollected", 0, null, null);

            assertThat(get(wrapsStaff, reportPath + "?startDate=2025-02-01&endDate=2025-02-01")
                    .statusCode()).isEqualTo(403);
            assertThat(get(employee, reportPath + "?startDate=2025-02-01&endDate=2025-02-01")
                    .statusCode()).isEqualTo(403);
            assertThat(get(thaliStaff, reportPath + "?startDate=2025-01-01&endDate=2025-02-01")
                    .statusCode()).isEqualTo(422);
            assertThat(get(thaliStaff, reportPath + "?startDate=2025-02-02&endDate=2025-02-01")
                    .statusCode()).isEqualTo(422);
        } finally {
            jdbc.update("UPDATE menu_item SET name = ?, price_minor = ? WHERE id = UUID_TO_BIN(?)",
                    originalName, originalPrice, itemId.toString());
        }
    }

    @Test
    @Order(15)
    void readinessGoesDownWhenDatabaseIsUnavailableButLivenessStaysUp() throws Exception {
        MYSQL.stop();

        HttpResponse<String> readiness = get("/actuator/health/readiness");
        HttpResponse<String> liveness = get("/actuator/health/liveness");

        assertThat(readiness.statusCode()).isEqualTo(503);
        assertThat(readiness.body()).contains("\"status\":\"DOWN\"");
        assertThat(liveness.statusCode()).isEqualTo(200);
        assertThat(liveness.body()).contains("\"status\":\"UP\"");
    }

    @Test
    @Order(14)
    void liveEventsAreCommittedScopedPrivacySafeAndRequireAnActiveSession() throws Exception {
        insertUser(LIVE_TEST_EMPLOYEE_ID, "employee.live.test", UserRole.EMPLOYEE);
        jdbc.update("UPDATE app_user SET password_hash = ? WHERE id = UUID_TO_BIN(?)",
                passwordEncoder.encode("integration-test-only-password"), LIVE_TEST_EMPLOYEE_ID.toString());

        HttpClient owner = loginEmployee("employee.live.test");
        HttpClient otherEmployee = loginEmployee("employee.demo.01");
        HttpClient assignedStaff = loginStaff("staff.north.thali");
        HttpClient otherCounterStaff = loginStaff("staff.south.rice");
        HttpClient admin = loginStaff("admin.demo");
        String ownerCsrf = csrfToken(get(owner, "/api/v1/auth/csrf"));
        String assignedStaffCsrf = csrfToken(get(assignedStaff, "/api/v1/auth/csrf"));
        String adminCsrf = csrfToken(get(admin, "/api/v1/auth/csrf"));

        jdbc.update("UPDATE menu_item SET availability = 'SOLD_OUT' WHERE counter_id = UUID_TO_BIN(?)",
                DEMO_NORTH_THALI_COUNTER_ID.toString());
        UUID orderId;
        try (EventStream ownerStream = openEvents(owner);
                EventStream otherEmployeeStream = openEvents(otherEmployee);
                EventStream assignedStaffStream = openEvents(assignedStaff);
                EventStream otherCounterStaffStream = openEvents(otherCounterStaff)) {
            HttpResponse<String> available = put(assignedStaff,
                    "/api/v1/staff/counters/" + DEMO_NORTH_THALI_COUNTER_ID + "/availability",
                    "{\"availability\":\"AVAILABLE\"}", assignedStaffCsrf);
            assertThat(available.statusCode()).isEqualTo(200);
            String ownerAvailability = ownerStream.awaitType("ITEM_AVAILABILITY_CHANGED", Duration.ofSeconds(3));
            String otherAvailability = otherEmployeeStream.awaitType("ITEM_AVAILABILITY_CHANGED", Duration.ofSeconds(3));
            String staffAvailability = assignedStaffStream.awaitType("ITEM_AVAILABILITY_CHANGED", Duration.ofSeconds(3));
            assertThat(ownerAvailability).contains("entityId", DEMO_NORTH_THALI_COUNTER_ID.toString())
                    .doesNotContain("pickup", "code");
            assertThat(otherAvailability).isNotNull();
            assertThat(staffAvailability).isNotNull();
            assertThat(otherCounterStaffStream.awaitType("ITEM_AVAILABILITY_CHANGED", Duration.ofMillis(250)))
                    .isNull();

            UUID slotId = recommendedWindowId(get(owner,
                    "/api/v1/counters/" + DEMO_NORTH_THALI_COUNTER_ID + "/order-options"));
            HttpResponse<String> created = postOrder(owner,
                    createOrderBody(DEMO_NORTH_THALI_COUNTER_ID, slotId), ownerCsrf, UUID.randomUUID());
            assertThat(created.statusCode()).isEqualTo(201);
            orderId = responseId(created);
            String ownerCapacity = ownerStream.awaitType("SLOT_CAPACITY_CHANGED", Duration.ofSeconds(3));
            String otherCapacity = otherEmployeeStream.awaitType("SLOT_CAPACITY_CHANGED", Duration.ofSeconds(3));
            String staffCapacity = assignedStaffStream.awaitType("SLOT_CAPACITY_CHANGED", Duration.ofSeconds(3));
            assertThat(ownerCapacity).contains(slotId.toString()).doesNotContain("code", "pickup");
            assertThat(otherCapacity).isNotNull();
            assertThat(staffCapacity).isNotNull();

            String ownerOrder = ownerStream.awaitType("ORDER_STATUS_CHANGED", Duration.ofSeconds(3));
            String assignedStaffOrder = assignedStaffStream.awaitType("ORDER_STATUS_CHANGED", Duration.ofSeconds(3));
            assertThat(ownerOrder).contains(orderId.toString()).doesNotContain("code", "pickup");
            assertThat(assignedStaffOrder).contains(orderId.toString()).doesNotContain("code", "pickup");
            assertThat(otherEmployeeStream.awaitType("ORDER_STATUS_CHANGED", Duration.ofMillis(250))).isNull();
            assertThat(otherCounterStaffStream.awaitType("ORDER_STATUS_CHANGED", Duration.ofMillis(250))).isNull();

            new TransactionTemplate(transactionManager).execute(status -> {
                liveEvents.counterChanged(LiveEventType.ITEM_AVAILABILITY_CHANGED, UUID.randomUUID(),
                        DEMO_NORTH_THALI_COUNTER_ID, 999, clock.instant());
                status.setRollbackOnly();
                return null;
            });
            assertThat(ownerStream.awaitType("ITEM_AVAILABILITY_CHANGED", Duration.ofMillis(250))).isNull();
            assertThat(otherEmployeeStream.awaitType("ITEM_AVAILABILITY_CHANGED", Duration.ofMillis(250))).isNull();
            assertThat(assignedStaffStream.awaitType("ITEM_AVAILABILITY_CHANGED", Duration.ofMillis(250))).isNull();

            assertThat(post(owner, "/api/v1/auth/logout", "", ownerCsrf).statusCode()).isEqualTo(204);
            jdbc.update("DELETE FROM staff_counter_assignment WHERE user_id = UUID_TO_BIN(?) AND counter_id = UUID_TO_BIN(?)",
                    DEMO_STAFF_NORTH_THALI_ID.toString(), DEMO_NORTH_THALI_COUNTER_ID.toString());
            HttpResponse<String> preparing = postTransition(admin,
                    "/api/v1/staff/orders/" + orderId + "/preparing", "", adminCsrf, "\"1\"");
            assertThat(preparing.statusCode()).isEqualTo(200);
            assertThat(ownerStream.awaitClosed(Duration.ofSeconds(3))).isTrue();
            assertThat(assignedStaffStream.awaitType("ORDER_STATUS_CHANGED", Duration.ofMillis(250))).isNull();
            assertThat(otherEmployeeStream.awaitType("ORDER_STATUS_CHANGED", Duration.ofMillis(250))).isNull();
            assertThat(otherCounterStaffStream.awaitType("ORDER_STATUS_CHANGED", Duration.ofMillis(250))).isNull();
        }

        HttpClient reconnectedOwner = loginEmployee("employee.live.test");
        try (EventStream reconnected = openEvents(reconnectedOwner)) {
            assertThat(reconnected.awaitType("ORDER_STATUS_CHANGED", Duration.ofMillis(250))).isNull();
            HttpResponse<String> snapshot = get(reconnectedOwner, "/api/v1/me/orders");
            assertThat(snapshot.statusCode()).isEqualTo(200);
            assertThat(snapshot.body()).contains(orderId.toString());
        }
        assertThat(get(newSessionClient(), "/api/v1/events").statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(HttpClient client, String path, String... headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(HttpClient client, String path, String body, String csrf)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(body));
        if (csrf != null) {
            request.header("X-XSRF-TOKEN", csrf);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postTransition(
            HttpClient client, String path, String body, String csrf, String ifMatch)
            throws IOException, InterruptedException {
        return client.send(transitionRequest(client, path, body, csrf, ifMatch),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postPickupRedemption(HttpClient client, String path, String code, String csrf)
            throws IOException, InterruptedException {
        return client.send(pickupRedemptionRequest(path, code, csrf), HttpResponse.BodyHandlers.ofString());
    }

    private CompletableFuture<HttpResponse<String>> postPickupRedemptionAsync(
            HttpClient client, String path, String code, String csrf) {
        return client.sendAsync(pickupRedemptionRequest(path, code, csrf), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest pickupRedemptionRequest(String path, String code, String csrf) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrf)
                .POST(BodyPublishers.ofString("{\"code\":\"" + code + "\"}"))
                .build();
    }

    private CompletableFuture<HttpResponse<String>> postTransitionAsync(
            HttpClient client, String path, String body, String csrf, String ifMatch) {
        return client.sendAsync(transitionRequest(client, path, body, csrf, ifMatch),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest transitionRequest(HttpClient client, String path, String body, String csrf, String ifMatch) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrf)
                .header("If-Match", ifMatch)
                .POST(BodyPublishers.ofString(body))
                .build();
    }

    private List<Instant> pickupWindowStarts(HttpResponse<String> response) {
        Matcher matcher = Pattern.compile("\"startAt\":\"([^\"]+)\"").matcher(response.body());
        List<Instant> starts = new ArrayList<>();
        while (matcher.find()) {
            starts.add(Instant.parse(matcher.group(1)));
        }
        return starts;
    }

    private HttpResponse<String> postOrder(HttpClient client, String body, String csrf, UUID idempotencyKey)
            throws IOException, InterruptedException {
        return client.send(orderRequest(body, csrf, idempotencyKey), HttpResponse.BodyHandlers.ofString());
    }

    private CompletableFuture<HttpResponse<String>> postOrderAsync(
            HttpClient client, String body, String csrf, UUID idempotencyKey) {
        return client.sendAsync(orderRequest(body, csrf, idempotencyKey), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest orderRequest(String body, String csrf, UUID idempotencyKey) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/orders"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrf)
                .header("Idempotency-Key", idempotencyKey.toString())
                .POST(BodyPublishers.ofString(body))
                .build();
    }

    private CompletableFuture<HttpResponse<String>> putAsync(
            HttpClient client, String path, String body, String csrf) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrf)
                .PUT(BodyPublishers.ofString(body))
                .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(HttpClient client, String path, String body, String csrf)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrf)
                .PUT(BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> patch(HttpClient client, String path, String body, String csrf)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrf)
                .method("PATCH", BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(HttpClient client, String path, String csrf)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("X-XSRF-TOKEN", csrf)
                .DELETE()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpClient loginStaff(String username) throws IOException, InterruptedException {
        HttpClient staff = newSessionClient();
        String csrf = csrfToken(get(staff, "/api/v1/auth/csrf"));
        String password = username.equals("staff.redemption.test") ? "demo-password"
                : "integration-test-only-password";
        HttpResponse<String> login = post(staff, "/api/v1/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}", csrf);
        assertThat(login.statusCode()).isEqualTo(200);
        return staff;
    }

    private HttpClient loginEmployee(String username) throws IOException, InterruptedException {
        HttpClient employee = newSessionClient();
        String csrf = csrfToken(get(employee, "/api/v1/auth/csrf"));
        String password = username.equals("employee.demo.04") ? "demo-password" : "integration-test-only-password";
        HttpResponse<String> login = post(employee, "/api/v1/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}", csrf);
        assertThat(login.statusCode()).isEqualTo(200);
        return employee;
    }

    private UUID recommendedWindowId(HttpResponse<String> quote) {
        assertThat(quote.statusCode()).isEqualTo(200);
        Matcher matcher = RECOMMENDED_ID_PATTERN.matcher(quote.body());
        assertThat(matcher.find()).isTrue();
        return UUID.fromString(matcher.group(1));
    }

    private String createOrderBody(UUID counterId, UUID slotId) {
        return "{\"counterId\":\"" + counterId + "\",\"pickupSlotId\":\"" + slotId + "\"}";
    }

    private String pickupCode(HttpResponse<String> response) {
        Matcher matcher = PICKUP_CODE_PATTERN.matcher(response.body());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private int allocationCount(UUID slotId) {
        return jdbc.queryForObject("SELECT confirmed_allocation_count FROM pickup_slot WHERE id = UUID_TO_BIN(?)",
                Integer.class, slotId.toString());
    }

    private UUID ownedOrderId(UUID employeeId, UUID counterId) {
        return jdbc.queryForObject("""
                SELECT BIN_TO_UUID(id) FROM meal_order
                WHERE employee_id = UUID_TO_BIN(?) AND counter_id = UUID_TO_BIN(?) AND status IN ('ACCEPTED', 'PREPARING', 'READY')
                ORDER BY placed_at DESC LIMIT 1
                """, (result, row) -> UUID.fromString(result.getString(1)), employeeId.toString(), counterId.toString());
    }

    private String pickupCodeFromDatabase(UUID orderId) {
        return jdbc.queryForObject("SELECT code FROM pickup_token WHERE order_id = UUID_TO_BIN(?)",
                String.class, orderId.toString());
    }

    private UUID responseId(HttpResponse<String> response) {
        Matcher matcher = RESPONSE_ID_PATTERN.matcher(response.body());
        assertThat(matcher.find()).isTrue();
        return UUID.fromString(matcher.group(1));
    }

    private HttpClient newSessionClient() {
        return HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .build();
    }

    private EventStream openEvents(HttpClient client) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/events"))
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        HttpResponse<InputStream> response = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .get(5, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse("")).contains("text/event-stream");
        assertThat(response.headers().firstValue("cache-control").orElse("")).contains("no-cache");
        assertThat(response.headers().firstValue("x-accel-buffering").orElse("")).isEqualTo("no");
        return new EventStream(response.body());
    }

    private static final class EventStream implements AutoCloseable {
        private static final Object STREAM_END = new Object();

        private final InputStream input;
        private final LinkedBlockingQueue<Object> frames = new LinkedBlockingQueue<>();

        private EventStream(InputStream input) {
            this.input = input;
            Thread.ofVirtual().name("xeats-sse-test-reader").start(this::readFrames);
        }

        private String awaitType(String type, Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                Object next = frames.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                if (next == null || next == STREAM_END) {
                    return null;
                }
                SseFrame frame = (SseFrame) next;
                if (frame.type().equals(type)) {
                    return frame.toString();
                }
            }
            return null;
        }

        private boolean awaitClosed(Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                Object next = frames.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                if (next == null) {
                    return false;
                }
                if (next == STREAM_END) {
                    return true;
                }
                return false;
            }
            return false;
        }

        private void readFrames() {
            String event = null;
            String id = null;
            StringBuilder data = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (event != null) {
                            frames.offer(new SseFrame(event, id, data.toString()));
                        }
                        event = null;
                        id = null;
                        data.setLength(0);
                    } else if (line.startsWith(":")) {
                        continue;
                    } else if (line.startsWith("event:")) {
                        event = line.substring("event:".length()).trim();
                    } else if (line.startsWith("id:")) {
                        id = line.substring("id:".length()).trim();
                    } else if (line.startsWith("data:")) {
                        if (!data.isEmpty()) {
                            data.append('\n');
                        }
                        data.append(line.substring("data:".length()).trim());
                    }
                }
            } catch (IOException ignored) {
                // Closing the response stream is the test client's disconnect signal.
            } finally {
                frames.offer(STREAM_END);
            }
        }

        @Override
        public void close() throws IOException {
            input.close();
        }
    }

    private record SseFrame(String type, String id, String data) { }

    private String csrfToken(HttpResponse<String> response) throws IOException {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"headerName\":\"X-XSRF-TOKEN\"");
        Matcher matcher = CSRF_TOKEN_PATTERN.matcher(response.body());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private String cookieValue(HttpResponse<String> response, String name) {
        return response.headers().allValues("set-cookie").stream()
                .filter(cookie -> cookie.startsWith(name + "="))
                .map(cookie -> cookie.substring(name.length() + 1).split(";", 2)[0])
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + name + " cookie was set."));
    }

    private String fullDayHours() {
        String intervals = java.util.Arrays.stream(DayOfWeek.values())
                .map(day -> "{\"day\":\"" + day.name() + "\",\"opensAt\":\"00:00:00\","
                        + "\"closesAt\":\"23:55:00\"}")
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"timeZone\":\"Asia/Kolkata\",\"intervals\":[" + intervals + "]}";
    }

    private String capacityPolicy(
            int slotDuration, int totalMeals, int walkInPercent, int leadSeconds, int horizonMinutes,
            int serviceSeconds) {
        return "{\"slotDurationMinutes\":" + slotDuration + ",\"totalMealsPerSlot\":" + totalMeals
                + ",\"walkInReservePercent\":" + walkInPercent
                + ",\"minimumPreparationLeadSeconds\":" + leadSeconds
                + ",\"maximumAdvanceMinutes\":" + horizonMinutes
                + ",\"serviceSecondsPerMeal\":" + serviceSeconds + "}";
    }

    private void seedIdentityFixture() {
        jdbc.update("INSERT INTO food_court (id, code, name) VALUES (UUID_TO_BIN(?), ?, ?)",
                FOOD_COURT_ID.toString(), "TEST", "Test Court");
        jdbc.update("INSERT INTO vendor (id, food_court_id, name) VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?)",
                VENDOR_ID.toString(), FOOD_COURT_ID.toString(), "Test Vendor");
        jdbc.update("INSERT INTO counter (id, vendor_id, name, pickup_instructions) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?)",
                COUNTER_ID.toString(), VENDOR_ID.toString(), "Test Counter", "Collect here");
        insertUser(EMPLOYEE_ID, "employee.one", UserRole.EMPLOYEE);
        insertUser(STAFF_ID, "staff.one", UserRole.VENDOR_STAFF);
        jdbc.update("INSERT INTO staff_counter_assignment (user_id, counter_id) "
                        + "VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?))",
                STAFF_ID.toString(), COUNTER_ID.toString());
    }

    private void insertUser(UUID userId, String username, UserRole role) {
        jdbc.update("INSERT INTO app_user (id, username, password_hash) VALUES (UUID_TO_BIN(?), ?, ?)",
                userId.toString(), username, passwordEncoder.encode("demo-password"));
        jdbc.update("INSERT INTO user_role (user_id, role) VALUES (UUID_TO_BIN(?), ?)",
                userId.toString(), role.name());
    }

    private void insertReportOrder(
            UUID orderId,
            UUID itemId,
            UUID slotId,
            String itemName,
            long itemPriceMinor,
            OrderStatus status,
            String placedAt,
            String acceptedAt,
            String preparingAt,
            String readyAt,
            String collectedAt,
            String expiredAt,
            String pickupWindowStartAt,
            String pickupWindowEndAt) {
        String[] counterSnapshot = jdbc.queryForObject(
                "SELECT name, pickup_instructions FROM counter WHERE id = UUID_TO_BIN(?)",
                (result, row) -> new String[] {result.getString("name"), result.getString("pickup_instructions")},
                DEMO_NORTH_THALI_COUNTER_ID.toString());
        OrderRecord order = new OrderRecord(orderId, DEMO_EMPLOYEE_ID, DEMO_NORTH_THALI_COUNTER_ID, itemId, slotId,
                itemName, itemPriceMinor, "INR", counterSnapshot[0], counterSnapshot[1],
                reportInstant(pickupWindowStartAt), reportInstant(pickupWindowEndAt), status, 1,
                "SIMULATED_SUCCEEDED", reportInstant(placedAt), reportInstant(acceptedAt),
                reportInstantOrNull(preparingAt), reportInstantOrNull(readyAt), reportInstantOrNull(collectedAt),
                reportInstantOrNull(expiredAt), status == OrderStatus.EXPIRED ? "Test report expiry" : null);
        orderRepository.insertOrder(order);
    }

    private static Instant reportInstant(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
    }

    private static Instant reportInstantOrNull(String localDateTime) {
        return localDateTime == null ? null : reportInstant(localDateTime);
    }

    private void assertJsonNumber(String json, String property, long expected) {
        assertThat(Pattern.compile("\\\"" + Pattern.quote(property) + "\\\":(\\d+)([,}])")
                .matcher(json).results().map(match -> Long.parseLong(match.group(1))).toList())
                .contains(expected);
    }

    private static int countOccurrences(String value, String needle) {
        int count = 0;
        int from = 0;
        while ((from = value.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    private void assertTiming(String json, String metric, long sampleCount, String average, String p95) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(metric)
                + "\\\":\\{\\\"sampleCount\\\":(\\d+),\\\"averageMinutes\\\":(null|[0-9.]+),"
                + "\\\"p95Minutes\\\":(null|[0-9.]+)\\}").matcher(json);
        assertThat(matcher.find()).isTrue();
        assertThat(Long.parseLong(matcher.group(1))).isEqualTo(sampleCount);
        if (average == null) {
            assertThat(matcher.group(2)).isEqualTo("null");
            assertThat(matcher.group(3)).isEqualTo("null");
        } else {
            assertThat(new java.math.BigDecimal(matcher.group(2)))
                    .isEqualByComparingTo(new java.math.BigDecimal(average));
            assertThat(new java.math.BigDecimal(matcher.group(3)))
                    .isEqualByComparingTo(new java.math.BigDecimal(p95));
        }
    }

    private void createOrderFixture(
            UUID orderId,
            UUID employeeId,
            UUID counterId,
            UUID slotId,
            String tokenCode,
            boolean createClaim) {
        Instant now = clock.instant();
        OrderRecord order = jdbc.query("""
                SELECT BIN_TO_UUID(i.id) AS item_id, i.name AS item_name, i.price_minor, i.currency,
                    c.name AS counter_name, c.pickup_instructions,
                    s.start_at, s.end_at
                FROM counter c
                JOIN menu_item i ON i.counter_id = c.id
                JOIN pickup_slot s ON s.counter_id = c.id
                WHERE c.id = UUID_TO_BIN(?) AND s.id = UUID_TO_BIN(?)
                """, (result, row) -> new OrderRecord(
                orderId, employeeId, counterId, UUID.fromString(result.getString("item_id")), slotId,
                result.getString("item_name"), result.getLong("price_minor"), result.getString("currency"),
                result.getString("counter_name"), result.getString("pickup_instructions"),
                result.getTimestamp("start_at").toInstant(), result.getTimestamp("end_at").toInstant(),
                OrderStatus.ACCEPTED, 1, "SIMULATED_SUCCEEDED", now, now, null, null, null, null, null),
                counterId.toString(), slotId.toString()).stream().findFirst().orElseThrow();
        orderRepository.insertOrder(order);
        if (tokenCode != null) {
            orderRepository.issuePickupToken(orderId, tokenCode, now);
            orderRepository.appendStatusEvent(orderId, 1, null, OrderStatus.ACCEPTED, employeeId, now, null);
        }
        if (createClaim) {
            orderRepository.createActiveOrderClaim(employeeId, counterId, orderId, now);
        }
    }

    private FixtureCounts fixtureCounts() {
        return new FixtureCounts(
                jdbc.queryForObject("SELECT COUNT(*) FROM food_court", Integer.class),
                jdbc.queryForObject("SELECT COUNT(*) FROM vendor", Integer.class),
                jdbc.queryForObject("SELECT COUNT(*) FROM counter", Integer.class),
                jdbc.queryForObject("SELECT COUNT(*) FROM menu_item", Integer.class),
                jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class),
                jdbc.queryForObject("SELECT COUNT(*) FROM staff_counter_assignment", Integer.class),
                jdbc.queryForObject("SELECT COUNT(*) FROM counter_opening_interval", Integer.class));
    }

    private record FixtureCounts(
            int foodCourts, int vendors, int counters, int items, int users, int assignments, int openingIntervals) {
    }
}
