package com.xeats.backend.catalog.management;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.xeats.backend.catalog.VendorServiceModel;
import com.xeats.backend.catalog.MenuItemAvailability;

public final class CatalogManagementDtos {
    private CatalogManagementDtos() { }

    public record CreateFoodCourtRequest(
            @NotBlank @Pattern(regexp = "[A-Z0-9-]{2,20}") String code,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Pattern(regexp = "Asia/Kolkata") String timeZone) { }

    public record CreateVendorRequest(
            @NotBlank @Size(max = 120) String name,
            VendorServiceModel serviceModel) { }

    public record MoneyRequest(
            @NotNull @Min(0) Long amountMinor,
            @NotBlank @Pattern(regexp = "INR") String currency) { }

    public record NewMenuItemRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,
            @Size(max = 60) String category,
            @NotNull @Valid MoneyRequest price) { }

    public record CapacityPolicyRequest(
            @NotNull @Min(5) @Max(30) Integer slotDurationMinutes,
            @NotNull @Min(1) @Max(200) Integer totalMealsPerSlot,
            @NotNull @Min(0) @Max(100) Integer walkInReservePercent,
            @NotNull @Min(0) @Max(1800) Integer minimumPreparationLeadSeconds,
            @NotNull @Min(15) @Max(1440) Integer maximumAdvanceMinutes,
            @NotNull @Min(15) @Max(600) Integer serviceSecondsPerMeal) { }

    public record OpeningIntervalRequest(
            @NotNull DayOfWeek day,
            @NotNull LocalTime opensAt,
            @NotNull LocalTime closesAt) { }

    public record OpeningHoursRequest(
            @NotBlank @Pattern(regexp = "Asia/Kolkata") String timeZone,
            @NotNull List<@NotNull @Valid OpeningIntervalRequest> intervals) { }

    public record CreateCounterRequest(
            @NotBlank @Size(max = 80) String name,
            @NotBlank @Size(max = 300) String pickupInstructions,
            @NotNull @Valid NewMenuItemRequest item,
            @NotNull @Valid CapacityPolicyRequest capacityPolicy,
            @NotNull @Valid OpeningHoursRequest openingHours) { }

    public record UpdateMenuItemRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,
            @Size(max = 60) String category,
            @NotNull @Valid MoneyRequest price) { }

    public record AvailabilityRequest(@NotNull MenuItemAvailability availability) { }

    public record OnlineAdmissionRequest(@NotNull Boolean acceptingOnlineOrders) { }

    public record CounterActiveRequest(@NotNull Boolean active) { }

    public record StaffAssignmentRequest(@NotNull UUID userId) { }

    public record OpeningInterval(DayOfWeek day, LocalTime opensAt, LocalTime closesAt) { }

    public record OpeningHours(String timeZone, List<OpeningInterval> intervals) {
        public OpeningHours {
            intervals = List.copyOf(intervals);
        }
    }
}
