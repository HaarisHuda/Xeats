package com.xeats.backend.reporting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.xeats.backend.catalog.discovery.DiscoveryDtos.Money;

/** Historical counter report values; simulated order amounts never represent settlement or revenue. */
public final class CounterReportDtos {
    private CounterReportDtos() { }

    public record CounterReport(
            String timeZone,
            LocalDate startDate,
            LocalDate endDate,
            long ordersAccepted,
            long ordersCollected,
            long ordersCollectedOnTime,
            long ordersExpired,
            Money simulatedAcceptedValue,
            Money simulatedCollectedValue,
            List<CollectedItem> mealsCollectedByItem,
            List<HourlyDemand> hourlyDistribution,
            LifecycleTimings timings) {
        public CounterReport {
            mealsCollectedByItem = List.copyOf(mealsCollectedByItem);
            hourlyDistribution = List.copyOf(hourlyDistribution);
        }
    }

    public record CollectedItem(String itemName, Money itemPrice, long count, Money simulatedValue) { }

    public record HourlyDemand(int localHour, long ordersPlaced) { }

    public record LifecycleTimings(
            TimingSummary acceptedToPreparing,
            TimingSummary preparingToReady,
            TimingSummary readyToCollected,
            TimingSummary placedToCollected) { }

    /** Durations are in minutes; averages and nearest-rank p95 are null when sampleCount is zero. */
    public record TimingSummary(long sampleCount, BigDecimal averageMinutes, BigDecimal p95Minutes) { }
}
