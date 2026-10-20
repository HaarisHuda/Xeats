package com.xeats.backend.catalog;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;

/** A recurring local-time opening interval. A close earlier than the open time crosses midnight. */
public record CounterOpeningInterval(
        UUID counterId,
        DayOfWeek weekday,
        LocalTime opensAt,
        LocalTime closesAt) {

    public CounterOpeningInterval {
        Objects.requireNonNull(counterId, "counterId");
        Objects.requireNonNull(weekday, "weekday");
        Objects.requireNonNull(opensAt, "opensAt");
        Objects.requireNonNull(closesAt, "closesAt");
        if (opensAt.equals(closesAt)) {
            throw new IllegalArgumentException("An opening interval must have nonzero duration.");
        }
    }
}
