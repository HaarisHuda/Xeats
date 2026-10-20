package com.xeats.backend.common.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Shared serialization point for order admission and operations on a counter. */
@Repository
public class CounterRowLockRepository {
    private final JdbcTemplate jdbc;

    public CounterRowLockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Must be called inside the surrounding transaction; the lock is held until that transaction completes. */
    public boolean lockForUpdate(UUID counterId) {
        List<String> matches = jdbc.query(
                "SELECT BIN_TO_UUID(id) FROM counter WHERE id = UUID_TO_BIN(?) FOR UPDATE",
                (result, row) -> result.getString(1), counterId.toString());
        return !matches.isEmpty();
    }
}
