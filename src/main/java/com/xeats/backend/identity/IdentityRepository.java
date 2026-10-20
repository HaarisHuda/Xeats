package com.xeats.backend.identity;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.xeats.backend.common.web.ApiErrorCode;
import com.xeats.backend.common.web.ApiProblemException;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IdentityRepository {
    private final JdbcTemplate jdbc;

    public IdentityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserAccount> findByUsername(String username) {
        List<UserAccount> accounts = jdbc.query(
                "SELECT BIN_TO_UUID(id) AS id, username, password_hash, enabled "
                        + "FROM app_user WHERE username = ?",
                (result, row) -> new UserAccount(
                        UUID.fromString(result.getString("id")),
                        result.getString("username"),
                        result.getString("password_hash"),
                        result.getBoolean("enabled")),
                username);
        if (accounts.isEmpty()) {
            return Optional.empty();
        }
        UserAccount account = accounts.getFirst();
        return Optional.of(new UserAccount(
                account.id(), account.username(), account.passwordHash(), account.enabled(),
                findRoles(account.id()), findAssignments(account.id())));
    }

    public CurrentUserResponse getCurrentUser(UUID userId) {
        List<String> usernames = jdbc.query(
                "SELECT username FROM app_user WHERE id = UUID_TO_BIN(?) AND enabled = TRUE",
                (result, row) -> result.getString("username"),
                userId.toString());
        if (usernames.isEmpty()) {
            throw new ApiProblemException(HttpStatus.UNAUTHORIZED, ApiErrorCode.UNAUTHENTICATED,
                    "Authentication is required.");
        }
        List<UserRole> roles = findRoles(userId).stream().sorted().toList();
        List<UUID> assignments = findAssignments(userId).stream().sorted().toList();
        return new CurrentUserResponse(userId, usernames.getFirst(), roles, assignments);
    }

    public boolean isEnabled(UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM app_user WHERE id = UUID_TO_BIN(?) AND enabled = TRUE)",
                Boolean.class,
                userId.toString()));
    }

    public boolean hasAnyActiveRole(UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM app_user u "
                        + "JOIN user_role r ON r.user_id = u.id "
                        + "WHERE u.id = UUID_TO_BIN(?) AND u.enabled = TRUE)",
                Boolean.class,
                userId.toString()));
    }

    public boolean hasActiveRole(UUID userId, UserRole role) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM app_user u "
                        + "JOIN user_role r ON r.user_id = u.id "
                        + "WHERE u.id = UUID_TO_BIN(?) AND u.enabled = TRUE AND r.role = ?)",
                Boolean.class,
                userId.toString(), role.name()));
    }

    public boolean hasCounterAssignment(UUID userId, UUID counterId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM app_user u "
                        + "JOIN user_role r ON r.user_id = u.id AND r.role = 'VENDOR_STAFF' "
                        + "JOIN staff_counter_assignment a ON a.user_id = u.id "
                        + "WHERE u.id = UUID_TO_BIN(?) AND u.enabled = TRUE "
                        + "AND a.counter_id = UUID_TO_BIN(?))",
                Boolean.class,
                userId.toString(), counterId.toString()));
    }

    private Set<UserRole> findRoles(UUID userId) {
        List<String> names = jdbc.query(
                "SELECT role FROM user_role WHERE user_id = UUID_TO_BIN(?) ORDER BY role",
                (result, row) -> result.getString("role"),
                userId.toString());
        Set<UserRole> roles = new LinkedHashSet<>();
        names.stream().map(UserRole::valueOf).forEach(roles::add);
        return Set.copyOf(roles);
    }

    private Set<UUID> findAssignments(UUID userId) {
        List<String> ids = jdbc.query(
                "SELECT BIN_TO_UUID(counter_id) AS counter_id "
                        + "FROM staff_counter_assignment WHERE user_id = UUID_TO_BIN(?) ORDER BY counter_id",
                (result, row) -> result.getString("counter_id"),
                userId.toString());
        Set<UUID> assignments = new LinkedHashSet<>();
        ids.stream().map(UUID::fromString).forEach(assignments::add);
        return Set.copyOf(assignments);
    }

    public record UserAccount(
            UUID id,
            String username,
            String passwordHash,
            boolean enabled,
            Set<UserRole> roles,
            Set<UUID> assignedCounterIds) {
        public UserAccount(UUID id, String username, String passwordHash, boolean enabled) {
            this(id, username, passwordHash, enabled, Set.of(), Set.of());
        }
    }
}
