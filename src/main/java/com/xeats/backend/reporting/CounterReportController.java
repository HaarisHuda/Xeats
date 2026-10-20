package com.xeats.backend.reporting;

import java.time.LocalDate;
import java.util.UUID;

import com.xeats.backend.identity.XeatsPrincipal;
import com.xeats.backend.reporting.CounterReportDtos.CounterReport;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/staff/counters")
public class CounterReportController {
    private final CounterReportService reports;

    public CounterReportController(CounterReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/{counterId}/report")
    public CounterReport getCounterReport(
            @PathVariable UUID counterId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            Authentication authentication) {
        return reports.getCounterReport(counterId, startDate, endDate,
                (XeatsPrincipal) authentication.getPrincipal());
    }
}
