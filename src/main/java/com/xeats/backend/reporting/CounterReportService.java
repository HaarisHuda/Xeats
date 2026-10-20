package com.xeats.backend.reporting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.xeats.backend.catalog.discovery.DiscoveryDtos.Money;
import com.xeats.backend.common.web.ApiErrorCode;
import com.xeats.backend.common.web.ApiProblemException;
import com.xeats.backend.identity.IdentityAuthorizationService;
import com.xeats.backend.identity.XeatsPrincipal;
import com.xeats.backend.reporting.CounterReportDtos.CollectedItem;
import com.xeats.backend.reporting.CounterReportDtos.CounterReport;
import com.xeats.backend.reporting.CounterReportDtos.HourlyDemand;
import com.xeats.backend.reporting.CounterReportDtos.LifecycleTimings;
import com.xeats.backend.reporting.CounterReportDtos.TimingSummary;
import com.xeats.backend.reporting.ReportingRepository.ReportOrder;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class CounterReportService {
    private static final int MAX_RANGE_DAYS = 31;
    private static final BigDecimal MILLIS_PER_MINUTE = BigDecimal.valueOf(60_000);

    private final ReportingRepository reports;
    private final IdentityAuthorizationService authorization;

    public CounterReportService(ReportingRepository reports, IdentityAuthorizationService authorization) {
        this.reports = reports;
        this.authorization = authorization;
    }

    public CounterReport getCounterReport(
            UUID counterId, LocalDate startDate, LocalDate endDate, XeatsPrincipal actor) {
        authorization.requireCounterOperationAccess(actor, counterId);
        validateRange(startDate, endDate);
        String timeZone = reports.findCounterTimeZone(counterId).orElseThrow(CounterReportService::notFound);
        ZoneId zone = ZoneId.of(timeZone);
        Instant fromInclusive = startDate.atStartOfDay(zone).toInstant();
        Instant toExclusive = endDate.plusDays(1).atStartOfDay(zone).toInstant();
        List<ReportOrder> rows = reports.findReportOrders(counterId, fromInclusive, toExclusive);
        return aggregate(timeZone, startDate, endDate, rows, zone, fromInclusive, toExclusive);
    }

    private CounterReport aggregate(
            String timeZone,
            LocalDate startDate,
            LocalDate endDate,
            List<ReportOrder> rows,
            ZoneId zone,
            Instant fromInclusive,
            Instant toExclusive) {
        long accepted = 0;
        long collected = 0;
        long collectedOnTime = 0;
        long expired = 0;
        long simulatedAccepted = 0;
        long simulatedCollected = 0;
        long[] hours = new long[24];
        Map<ItemKey, ItemTotals> items = new HashMap<>();
        TimingAccumulator acceptedToPreparing = new TimingAccumulator();
        TimingAccumulator preparingToReady = new TimingAccumulator();
        TimingAccumulator readyToCollected = new TimingAccumulator();
        TimingAccumulator placedToCollected = new TimingAccumulator();

        for (ReportOrder row : rows) {
            if (within(row.placedAt(), fromInclusive, toExclusive)) {
                int localHour = row.placedAt().atZone(zone).getHour();
                hours[localHour] = Math.addExact(hours[localHour], 1);
            }
            if (within(row.acceptedAt(), fromInclusive, toExclusive)) {
                accepted = Math.addExact(accepted, 1);
                simulatedAccepted = Math.addExact(simulatedAccepted, row.itemPriceMinor());
            }
            if (row.status() == com.xeats.backend.ordering.OrderStatus.COLLECTED
                    && within(row.collectedAt(), fromInclusive, toExclusive)) {
                collected = Math.addExact(collected, 1);
                simulatedCollected = Math.addExact(simulatedCollected, row.itemPriceMinor());
                if (row.collectedAt().isBefore(row.pickupWindowEndAt())) {
                    collectedOnTime = Math.addExact(collectedOnTime, 1);
                }
                ItemKey key = new ItemKey(row.itemName(), row.itemPriceMinor(), row.currency());
                ItemTotals total = items.computeIfAbsent(key, ignored -> new ItemTotals());
                total.count = Math.addExact(total.count, 1);
                total.simulatedValue = Math.addExact(total.simulatedValue, row.itemPriceMinor());
                acceptedToPreparing.add(row.acceptedAt(), row.preparingAt());
                preparingToReady.add(row.preparingAt(), row.readyAt());
                readyToCollected.add(row.readyAt(), row.collectedAt());
                placedToCollected.add(row.placedAt(), row.collectedAt());
            }
            if (row.status() == com.xeats.backend.ordering.OrderStatus.EXPIRED
                    && within(row.expiredAt(), fromInclusive, toExclusive)) {
                expired = Math.addExact(expired, 1);
            }
        }

        List<CollectedItem> collectedItems = items.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ItemKey::name)
                        .thenComparingLong(ItemKey::priceMinor).thenComparing(ItemKey::currency)))
                .map(entry -> new CollectedItem(entry.getKey().name(),
                        new Money(entry.getKey().priceMinor(), entry.getKey().currency()),
                        entry.getValue().count,
                        new Money(entry.getValue().simulatedValue, entry.getKey().currency())))
                .toList();
        List<HourlyDemand> hourly = new ArrayList<>(24);
        for (int hour = 0; hour < hours.length; hour++) {
            hourly.add(new HourlyDemand(hour, hours[hour]));
        }

        return new CounterReport(timeZone, startDate, endDate, accepted, collected, collectedOnTime, expired,
                new Money(simulatedAccepted, "INR"), new Money(simulatedCollected, "INR"), collectedItems, hourly,
                new LifecycleTimings(acceptedToPreparing.summary(), preparingToReady.summary(),
                        readyToCollected.summary(), placedToCollected.summary()));
    }

    private static void validateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null || endDate.isBefore(startDate)
                || ChronoUnit.DAYS.between(startDate, endDate) >= MAX_RANGE_DAYS) {
            throw new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR,
                    "The report date range must be ordered and cover no more than 31 inclusive local dates.");
        }
        if (endDate.equals(LocalDate.MAX)) {
            throw new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR,
                    "The report end date is outside the supported date range.");
        }
    }

    private static boolean within(Instant value, Instant fromInclusive, Instant toExclusive) {
        return value != null && !value.isBefore(fromInclusive) && value.isBefore(toExclusive);
    }

    private static ApiProblemException notFound() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, ApiErrorCode.NOT_FOUND,
                "The requested resource was not found.");
    }

    private record ItemKey(String name, long priceMinor, String currency) { }

    private static final class ItemTotals {
        private long count;
        private long simulatedValue;
    }

    private static final class TimingAccumulator {
        private final List<Duration> samples = new ArrayList<>();

        private void add(Instant start, Instant end) {
            if (start == null || end == null) {
                return;
            }
            Duration duration = Duration.between(start, end);
            if (!duration.isNegative()) {
                samples.add(duration);
            }
        }

        private TimingSummary summary() {
            if (samples.isEmpty()) {
                return new TimingSummary(0, null, null);
            }
            List<Duration> ordered = samples.stream().sorted().toList();
            BigDecimal sumMinutes = ordered.stream()
                    .map(CounterReportService::toMinutes)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal averageMinutes = sumMinutes.divide(BigDecimal.valueOf(ordered.size()), 2,
                    RoundingMode.HALF_UP);
            int p95Rank = (int) Math.ceil(0.95d * ordered.size());
            BigDecimal p95Minutes = toMinutes(ordered.get(p95Rank - 1));
            return new TimingSummary(ordered.size(), averageMinutes, p95Minutes);
        }
    }

    private static BigDecimal toMinutes(Duration duration) {
        return BigDecimal.valueOf(duration.toMillis()).divide(MILLIS_PER_MINUTE, 2, RoundingMode.HALF_UP);
    }
}
