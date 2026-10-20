package com.xeats.backend.catalog.management;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.xeats.backend.catalog.MenuItemAvailability;
import com.xeats.backend.catalog.VendorServiceModel;
import com.xeats.backend.catalog.discovery.DiscoveryDtos.CounterSummary;
import com.xeats.backend.catalog.discovery.DiscoveryDtos.FoodCourt;
import com.xeats.backend.catalog.discovery.DiscoveryDtos.MenuItem;
import com.xeats.backend.catalog.discovery.DiscoveryDtos.Money;
import com.xeats.backend.catalog.discovery.DiscoveryDtos.Vendor;
import com.xeats.backend.catalog.management.CatalogManagementDtos.AvailabilityRequest;
import com.xeats.backend.catalog.management.CatalogManagementDtos.CapacityPolicyRequest;
import com.xeats.backend.catalog.management.CatalogManagementDtos.CreateCounterRequest;
import com.xeats.backend.catalog.management.CatalogManagementDtos.CreateFoodCourtRequest;
import com.xeats.backend.catalog.management.CatalogManagementDtos.CreateVendorRequest;
import com.xeats.backend.catalog.management.CatalogManagementDtos.OpeningHours;
import com.xeats.backend.catalog.management.CatalogManagementDtos.OpeningHoursRequest;
import com.xeats.backend.catalog.management.CatalogManagementDtos.OnlineAdmissionRequest;
import com.xeats.backend.catalog.management.CatalogManagementDtos.StaffAssignmentRequest;
import com.xeats.backend.catalog.management.CatalogManagementDtos.UpdateMenuItemRequest;
import com.xeats.backend.common.web.ApiErrorCode;
import com.xeats.backend.common.web.ApiProblemException;
import com.xeats.backend.events.LiveEventPublisher;
import com.xeats.backend.events.LiveEventType;
import com.xeats.backend.identity.IdentityAuthorizationService;
import com.xeats.backend.identity.XeatsPrincipal;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogManagementService {
    private static final Set<Integer> SLOT_DURATIONS = Set.of(5, 10, 15, 20, 25, 30);
    private static final int DAY_SECONDS = 24 * 60 * 60;
    private static final int WEEK_SECONDS = 7 * DAY_SECONDS;

    private final CatalogManagementRepository repository;
    private final IdentityAuthorizationService authorization;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final LiveEventPublisher liveEvents;

    public CatalogManagementService(
            CatalogManagementRepository repository,
            IdentityAuthorizationService authorization,
            ObjectMapper objectMapper,
            Clock clock,
            LiveEventPublisher liveEvents) {
        this.repository = repository;
        this.authorization = authorization;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.liveEvents = liveEvents;
    }

    @Transactional
    public FoodCourt createFoodCourt(CreateFoodCourtRequest request) {
        UUID id = UUID.randomUUID();
        String code = request.code().trim();
        if (repository.foodCourtCodeExists(code)) {
            throw conflict("A food court already uses this code.");
        }
        try {
            repository.insertFoodCourt(id, code, request.name().trim(), request.timeZone());
        } catch (DuplicateKeyException exception) {
            throw conflict("A food court already uses this code.");
        }
        return new FoodCourt(id, code, request.name().trim(), request.timeZone());
    }

    @Transactional
    public Vendor createVendor(UUID foodCourtId, CreateVendorRequest request) {
        FoodCourt court = repository.findActiveFoodCourt(foodCourtId)
                .orElseThrow(CatalogManagementService::notFound);
        UUID vendorId = UUID.randomUUID();
        VendorServiceModel serviceModel = request.serviceModel() == null
                ? VendorServiceModel.INDEPENDENT
                : request.serviceModel();
        repository.insertVendor(vendorId, court.id(), request.name().trim(), serviceModel);
        return new Vendor(vendorId, court.id(), request.name().trim());
    }

    @Transactional
    public CounterSummary createCounter(UUID vendorId, CreateCounterRequest request, XeatsPrincipal actor) {
        String expectedTimeZone = repository.findActiveFoodCourtTimeZoneForVendor(vendorId)
                .orElseThrow(CatalogManagementService::notFound);
        validateCapacityPolicy(request.capacityPolicy());
        validateHours(request.openingHours(), expectedTimeZone, request.capacityPolicy().slotDurationMinutes());

        UUID counterId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        repository.insertCounter(counterId, vendorId, request.name().trim(), request.pickupInstructions().trim());
        repository.insertMenuItem(itemId, counterId, request.item().name().trim(),
                cleanOptional(request.item().description()), cleanOptional(request.item().category()),
                request.item().price().amountMinor());
        repository.insertCapacityPolicy(counterId, request.capacityPolicy(),
                java.sql.Timestamp.from(clock.instant()));
        repository.replaceOpeningIntervals(counterId, request.openingHours().intervals());
        recordChange(counterId, actor.userId(), "COUNTER_CREATED", null,
                new CounterCreatedChange(vendorId, request.name().trim(), request.pickupInstructions().trim(),
                        itemId, new ItemChange(request.item().name().trim(),
                                cleanOptional(request.item().description()), cleanOptional(request.item().category()),
                                request.item().price().amountMinor(), request.item().price().currency()),
                        request.capacityPolicy(), request.openingHours()));
        return repository.findCounterSummary(counterId).orElseThrow(CatalogManagementService::notFound);
    }

    @Transactional
    public void assignStaff(UUID counterId, StaffAssignmentRequest request, XeatsPrincipal actor) {
        requireCounter(counterId);
        if (!repository.isActiveVendorStaff(request.userId())) {
            throw invalid("The selected account is not an active vendor-staff account.");
        }
        if (repository.insertStaffAssignmentIfMissing(counterId, request.userId()) > 0) {
            recordChange(counterId, actor.userId(), "STAFF_ASSIGNMENT", null,
                    new StaffAssignmentChange(request.userId(), true));
        }
    }

    @Transactional
    public void removeStaff(UUID counterId, UUID userId, XeatsPrincipal actor) {
        requireCounter(counterId);
        if (!repository.userExists(userId)) {
            throw notFound();
        }
        if (repository.removeStaffAssignment(counterId, userId) > 0) {
            recordChange(counterId, actor.userId(), "STAFF_ASSIGNMENT",
                    new StaffAssignmentChange(userId, true), new StaffAssignmentChange(userId, false));
        }
    }

    @Transactional
    public CounterSummary setCounterActive(UUID counterId, boolean active, XeatsPrincipal actor) {
        requireCounter(counterId);
        CounterSummary previous = currentSummary(counterId);
        if (previous.active() != active) {
            repository.updateActive(counterId, active);
            long changeId = recordChange(counterId, actor.userId(), "ACTIVE", Map.of("active", previous.active()),
                    Map.of("active", active));
            liveEvents.counterChanged(LiveEventType.COUNTER_ADMISSION_CHANGED, counterId, counterId,
                    changeId, clock.instant());
        }
        return currentSummary(counterId);
    }

    @Transactional
    public MenuItem updateItem(UUID counterId, UpdateMenuItemRequest request, XeatsPrincipal actor) {
        requireOperator(counterId, actor);
        requireCounter(counterId);
        com.xeats.backend.catalog.MenuItem previous = currentItem(counterId);
        ItemChange next = new ItemChange(request.name().trim(), cleanOptional(request.description()),
                cleanOptional(request.category()), request.price().amountMinor(), request.price().currency());
        ItemChange before = itemChange(previous);
        if (!before.equals(next)) {
            repository.updateMenuItem(counterId, request);
            recordChange(counterId, actor.userId(), "MENU_ITEM", before, next);
        }
        return currentMenuItem(counterId);
    }

    @Transactional
    public MenuItem setAvailability(UUID counterId, AvailabilityRequest request, XeatsPrincipal actor) {
        requireOperator(counterId, actor);
        requireCounter(counterId);
        com.xeats.backend.catalog.MenuItem previous = currentItem(counterId);
        if (previous.availability() != request.availability()) {
            repository.updateAvailability(counterId, request.availability());
            long changeId = recordChange(counterId, actor.userId(), "AVAILABILITY",
                    Map.of("availability", previous.availability()), Map.of("availability", request.availability()));
            liveEvents.counterChanged(LiveEventType.ITEM_AVAILABILITY_CHANGED, previous.id(), counterId,
                    changeId, clock.instant());
        }
        return currentMenuItem(counterId);
    }

    @Transactional
    public CounterSummary setOnlineAdmission(
            UUID counterId, OnlineAdmissionRequest request, XeatsPrincipal actor) {
        requireOperator(counterId, actor);
        requireCounter(counterId);
        CounterSummary previous = currentSummary(counterId);
        if (previous.acceptingOnlineOrders() != request.acceptingOnlineOrders()) {
            repository.updateOnlineAdmission(counterId, request.acceptingOnlineOrders());
            long changeId = recordChange(counterId, actor.userId(), "ACCEPTING_ONLINE_ORDERS",
                    Map.of("acceptingOnlineOrders", previous.acceptingOnlineOrders()),
                    Map.of("acceptingOnlineOrders", request.acceptingOnlineOrders()));
            liveEvents.counterChanged(LiveEventType.COUNTER_ADMISSION_CHANGED, counterId, counterId,
                    changeId, clock.instant());
        }
        return currentSummary(counterId);
    }

    @Transactional
    public OpeningHours replaceOpeningHours(
            UUID counterId, OpeningHoursRequest request, XeatsPrincipal actor) {
        requireOperator(counterId, actor);
        requireCounter(counterId);
        String expectedTimeZone = repository.findCounterTimeZone(counterId);
        validateHours(request, expectedTimeZone, repository.findLatestCapacitySlotDuration(counterId));
        OpeningHours previous = repository.findOpeningHours(counterId, expectedTimeZone);
        repository.replaceOpeningIntervals(counterId, request.intervals());
        OpeningHours next = repository.findOpeningHours(counterId, expectedTimeZone);
        if (!previous.equals(next)) {
            long changeId = recordChange(counterId, actor.userId(), "OPENING_HOURS", previous, next);
            liveEvents.counterChanged(LiveEventType.OPENING_HOURS_CHANGED, counterId, counterId,
                    changeId, clock.instant());
        }
        return next;
    }

    private void requireOperator(UUID counterId, XeatsPrincipal actor) {
        authorization.requireCounterOperationAccess(actor, counterId);
    }

    private void requireCounter(UUID counterId) {
        if (!repository.lockCounter(counterId)) {
            throw notFound();
        }
    }

    private CounterSummary currentSummary(UUID counterId) {
        return repository.findCounterSummary(counterId).orElseThrow(CatalogManagementService::notFound);
    }

    private com.xeats.backend.catalog.MenuItem currentItem(UUID counterId) {
        return repository.findMenuItem(counterId).orElseThrow(CatalogManagementService::notFound);
    }

    private MenuItem currentMenuItem(UUID counterId) {
        com.xeats.backend.catalog.MenuItem item = currentItem(counterId);
        return new MenuItem(item.id(), item.name(), item.description(), item.category(),
                new Money(item.priceMinor(), item.currency()), item.availability());
    }

    private void validateCapacityPolicy(CapacityPolicyRequest policy) {
        if (!SLOT_DURATIONS.contains(policy.slotDurationMinutes())) {
            throw invalid("Slot duration must be one of 5, 10, 15, 20, 25 or 30 minutes.");
        }
    }

    private void validateHours(OpeningHoursRequest hours, String expectedTimeZone, int slotDurationMinutes) {
        if (!expectedTimeZone.equals(hours.timeZone())) {
            throw invalid("Opening-hours timezone must match the food court timezone.");
        }

        List<OpeningSegment> segments = new ArrayList<>();
        for (OpeningHoursRequestInterval interval : normalize(hours.intervals())) {
            LocalTime open = interval.opensAt();
            LocalTime close = interval.closesAt();
            if (open.equals(close) || open.getNano() != 0 || close.getNano() != 0) {
                throw invalid("Opening intervals must have a nonzero duration and whole-second times.");
            }
            int start = (interval.day().getValue() - 1) * DAY_SECONDS + open.toSecondOfDay();
            int end = (interval.day().getValue() - 1) * DAY_SECONDS + close.toSecondOfDay();
            if (!close.isAfter(open)) {
                end += DAY_SECONDS;
            }
            if ((end - start) % (slotDurationMinutes * 60) != 0) {
                throw invalid("Opening intervals must end on a slot-duration boundary.");
            }
            if (end <= WEEK_SECONDS) {
                segments.add(new OpeningSegment(start, end));
            } else {
                segments.add(new OpeningSegment(start, WEEK_SECONDS));
                segments.add(new OpeningSegment(0, end - WEEK_SECONDS));
            }
        }
        segments.sort(Comparator.comparingInt(OpeningSegment::start).thenComparingInt(OpeningSegment::end));
        int furthestEnd = -1;
        for (OpeningSegment segment : segments) {
            if (segment.start() < furthestEnd) {
                throw invalid("Opening intervals must not overlap, including across midnight.");
            }
            furthestEnd = Math.max(furthestEnd, segment.end());
        }
    }

    private List<OpeningHoursRequestInterval> normalize(
            List<CatalogManagementDtos.OpeningIntervalRequest> intervals) {
        return intervals.stream().map(interval -> new OpeningHoursRequestInterval(
                interval.day(), interval.opensAt(), interval.closesAt())).toList();
    }

    private long recordChange(UUID counterId, UUID actorId, String field, Object previous, Object next) {
        return repository.insertChangeEvent(counterId, actorId, field, json(previous), json(next),
                java.sql.Timestamp.from(clock.instant()));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize the counter audit record.", exception);
        }
    }

    private static ItemChange itemChange(com.xeats.backend.catalog.MenuItem item) {
        return new ItemChange(item.name(), item.description(), item.category(), item.priceMinor(), item.currency());
    }

    private static String cleanOptional(String value) {
        return value == null ? null : value.trim();
    }

    private static ApiProblemException notFound() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, ApiErrorCode.NOT_FOUND,
                "The requested resource was not found.");
    }

    private static ApiProblemException invalid(String detail) {
        return new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.VALIDATION_ERROR, detail);
    }

    private static ApiProblemException conflict(String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, ApiErrorCode.CONFLICT, detail);
    }

    private record ItemChange(String name, String description, String category, long priceMinor, String currency) { }

    private record CounterCreatedChange(
            UUID vendorId,
            String counterName,
            String pickupInstructions,
            UUID itemId,
            ItemChange item,
            CapacityPolicyRequest capacityPolicy,
            OpeningHoursRequest openingHours) { }

    private record StaffAssignmentChange(UUID userId, boolean assigned) { }

    private record OpeningSegment(int start, int end) { }

    private record OpeningHoursRequestInterval(DayOfWeek day, LocalTime opensAt, LocalTime closesAt) { }
}
