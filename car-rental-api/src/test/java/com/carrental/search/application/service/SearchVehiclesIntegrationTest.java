package com.carrental.search.application.service;

import com.carrental.PostgresTestConfiguration;
import com.carrental.availability.api.AvailabilityDirectory;
import com.carrental.availability.api.BlockKind;
import com.carrental.availability.api.Period;
import com.carrental.booking.api.RentalTermsDirectory;
import com.carrental.search.SearchIntegrationTestConfiguration;
import com.carrental.search.SearchTestQueries;
import com.carrental.search.application.port.in.SearchVehiclesUseCase;
import com.carrental.search.application.view.SearchVehicleListItem;
import com.carrental.search.application.view.SearchVehiclesPage;
import com.carrental.search.domain.VehicleSearchFilters;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.rental.RentalType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm search → booking/branch/vehicle/availability → SQL/PostGIS thật theo BR-125.
 * Mỗi test rollback fixture; không mock directory, không đọc hoặc sửa database local.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "car-rental.availability.hold-duration=PT1H")
@Import({PostgresTestConfiguration.class, SearchIntegrationTestConfiguration.class})
@Transactional
class SearchVehiclesIntegrationTest {
    @Autowired private SearchVehiclesUseCase search;
    @Autowired private AvailabilityDirectory availability;
    @Autowired private RentalTermsDirectory terms;
    @Autowired private SearchIntegrationTestConfiguration.Fixtures fixtures;
    private SearchTestQueries.Input input;
    private String model;

    /** Cô lập tập ứng viên theo model ngẫu nhiên; vẫn đi qua bộ lọc production thật. */
    @BeforeEach
    void setUp() {
        model = "SearchModel-" + UUID.randomUUID();
        input = SearchTestQueries.input();
        input.filters = new VehicleSearchFilters(null, null, null, null, model, null);
    }

    /** BR-010/112/125/808: chỉ ACTIVE trong bán kính, gần trước dù ID lớn hơn, đủ dữ liệu hiển thị. */
    @Test
    void findsActiveNearbyVehiclesOrderedByActualDistance() {
        var near = fixtures.branch(input.latitude + 0.001, input.longitude);
        var origin = fixtures.branch(input.latitude, input.longitude);
        var far = fixtures.branch(input.latitude + 1, input.longitude);
        var nearVehicle = fixtures.active(near.code(), model);
        var originVehicle = fixtures.active(origin.code(), model);
        fixtures.active(far.code(), model);
        fixtures.draft(origin.code(), model);
        var page = search.search(input.build());
        assertCodes(page, originVehicle.code(), nearVehicle.code());
        var first = page.items().getFirst();
        assertEquals(0, first.distanceMeters(), 0.000001);
        assertTrue(page.items().getLast().distanceMeters() > 100);
        assertTrue(page.items().getLast().distanceMeters() < 120);
        assertEquals(origin.code(), first.branch().code());
        assertEquals(origin.name(), first.branch().name());
        assertEquals(origin.address(), first.branch().address());
        assertEquals("Toyota", first.make());
        assertEquals(model, first.model());
        assertEquals(5, first.seats());
        assertEquals("AUTOMATIC", first.transmission());
        assertEquals("PETROL", first.fuelType());
        assertTrue(first.collateralFree());
        assertNull(page.nextCursor());
        input.radiusKm = 0.05;
        assertCodes(search.search(input.build()), originVehicle.code());
    }

    /** BR-126/110: bộ lọc kết hợp, bỏ trắng và hoa thường; không khớp chuỗi con hay sai thế chấp. */
    @Test
    void appliesFiltersThroughRealVehicleDirectory() {
        var branch = fixtures.branch(input.latitude, input.longitude);
        var vehicle = fixtures.active(branch.code(), model);
        fixtures.active(branch.code(), "OtherModel");
        input.filters = new VehicleSearchFilters(5, "AUTOMATIC", "PETROL", "  tOyOtA  ",
                "  " + model.toLowerCase(java.util.Locale.ROOT) + "  ", true);
        assertCodes(search.search(input.build()), vehicle.code());
        input.filters = new VehicleSearchFilters(7, "AUTOMATIC", "PETROL", "Toyota", model, true);
        assertCodes(search.search(input.build()));
        input.filters = new VehicleSearchFilters(null, null, null, "Toy", model, null);
        assertCodes(search.search(input.build()));
        input.filters = new VehicleSearchFilters(null, null, null, null, model, false);
        assertCodes(search.search(input.build()));
    }

    /** BR-109/116: đệm của lượt trước chỉ cộng một lần, đúng cận cuối [) thì xe xuất hiện lại. */
    @ParameterizedTest
    @CsvSource({"HOURLY,1", "DAILY,2"})
    void respectsStoredBufferUntilExactExclusiveEnd(RentalType type, long hours) {
        var branch = fixtures.branch(input.latitude, input.longitude);
        var vehicle = fixtures.active(branch.code(), model);
        input.rentalType = type;
        var query = input.build();
        var buffer = terms.getTerms(type, query.pickupMethod(), input.start, input.end).turnaroundBuffer();
        assertEquals(Duration.ofHours(hours), buffer);
        Instant previousEnd = Instant.parse("2030-01-15T03:00:00Z");
        availability.hold(vehicle.id(), new Period(previousEnd.minus(Duration.ofHours(4)), previousEnd),
                buffer, "previous-rental");
        input.start = previousEnd.plus(buffer).minus(Duration.ofHours(1));
        input.end = input.start.plus(Duration.ofHours(4));
        assertCodes(search.search(input.build()));
        input.start = previousEnd.plus(buffer).minusSeconds(1);
        input.end = input.start.plus(Duration.ofHours(4));
        assertCodes(search.search(input.build()));
        input.start = previousEnd.plus(buffer);
        input.end = input.start.plus(Duration.ofHours(4));
        assertCodes(search.search(input.build()), vehicle.code());
    }

    /** BR-109/116: đệm của yêu cầu mới chạm khóa sau thì được, chồng một giây thì bị loại. */
    @ParameterizedTest
    @CsvSource({"HOURLY,1", "DAILY,2"})
    void includesNewRequestsBufferBeforeNextBlock(RentalType type, long hours) {
        var branch = fixtures.branch(input.latitude, input.longitude);
        var vehicle = fixtures.active(branch.code(), model);
        input.rentalType = type;
        Instant blockedAt = input.end.plus(Duration.ofHours(hours));
        availability.block(vehicle.id(), new Period(blockedAt, blockedAt.plusSeconds(3600)),
                BlockKind.MAINTENANCE, "Scheduled service");
        assertCodes(search.search(input.build()), vehicle.code());
        input.end = input.end.plusSeconds(1);
        assertCodes(search.search(input.build()));
    }

    /** BR-104: mọi khóa vận hành hữu hạn đều làm xe biến mất, không chỉ đơn thuê. */
    @ParameterizedTest
    @EnumSource(value = BlockKind.class, mode = EnumSource.Mode.EXCLUDE, names = "COMPLIANCE_HOLD")
    void excludesOperationalBlocks(BlockKind kind) {
        var branch = fixtures.branch(input.latitude, input.longitude);
        var vehicle = fixtures.active(branch.code(), model);
        availability.block(vehicle.id(), new Period(input.start, input.end), kind, "Operational fixture");
        assertCodes(search.search(input.build()));
    }

    /** BR-104/109: HELD, CONFIRMED, IN_USE, COMPLETED đều chặn; RELEASED trả xe vào tìm kiếm. */
    @Test
    void readsRealReservationLifecycleWithoutTreatingCompletionAsRelease() {
        var branch = fixtures.branch(input.latitude, input.longitude);
        var completedVehicle = fixtures.active(branch.code(), model);
        var releasedVehicle = fixtures.active(branch.code(), model);
        var completed = availability.hold(completedVehicle.id(), new Period(input.start, input.end),
                Duration.ofHours(2), "completed-rental");
        var released = availability.hold(releasedVehicle.id(), new Period(input.start, input.end),
                Duration.ofHours(2), "released-rental");
        assertCodes(search.search(input.build()));
        availability.confirm(completed.code());
        assertCodes(search.search(input.build()));
        availability.markInUse(completed.code());
        assertCodes(search.search(input.build()));
        availability.complete(completed.code());
        assertCodes(search.search(input.build()));
        availability.release(released.code());
        assertCodes(search.search(input.build()), releasedVehicle.code());
    }

    /** BR-015: khóa vô hạn chặn cả tương lai; gia hạn giấy tờ dời mốc thì khoảng vừa mở xuất hiện lại. */
    @Test
    void respectsUnboundedComplianceHoldAndItsMovedStart() {
        var branch = fixtures.branch(input.latitude, input.longitude);
        var vehicle = fixtures.active(branch.code(), model);
        var block = availability.block(vehicle.id(), new Period(input.start, null),
                BlockKind.COMPLIANCE_HOLD, "Inspection expired");
        assertCodes(search.search(input.build()));
        input.start = input.start.plus(Duration.ofDays(30));
        input.end = input.end.plus(Duration.ofDays(30));
        assertCodes(search.search(input.build()));
        availability.moveComplianceHoldStart(block.code(), input.end.plus(Duration.ofHours(2)));
        assertCodes(search.search(input.build()), vehicle.code());
        input.end = input.end.plusSeconds(1);
        assertCodes(search.search(input.build()));
    }

    /** BR-119: giờ trả 23:00 hợp lệ dù phần đệm đi sang ngày sau. */
    @Test
    void permitsReturnAtClosingTimeWithBufferOutsideBranchHours() {
        var branch = fixtures.branch(input.latitude, input.longitude);
        var vehicle = fixtures.active(branch.code(), model);
        input.end = Instant.parse("2030-01-15T16:00:00Z");
        assertCodes(search.search(input.build()), vehicle.code());
    }

    /** BR-113/119/121: lỗi điều kiện thuê phải đi qua booking thật kể cả khi không có ứng viên. */
    @ParameterizedTest
    @CsvSource({
            "HOURLY,2030-01-15T03:00:00Z,2030-01-15T06:59:59Z,RENTAL_DURATION_TOO_SHORT",
            "DAILY,2030-01-15T03:00:00Z,2030-01-15T16:00:01Z,OUTSIDE_BRANCH_HOURS",
            "DAILY,2030-01-15T00:59:59Z,2030-01-15T09:00:00Z,BOOKING_WINDOW_VIOLATION",
            "DAILY,2030-07-15T00:00:01Z,2030-07-15T09:00:00Z,BOOKING_WINDOW_VIOLATION"
    })
    void rejectsInvalidTermsEvenWithoutCandidates(RentalType type, String start, String end, ErrorCode expected) {
        input.rentalType = type;
        input.start = Instant.parse(start);
        input.end = Instant.parse(end);
        var failure = assertThrows(DomainException.class, () -> search.search(input.build()));
        assertEquals(expected, failure.errorCode());
        assertEquals(DomainException.Category.RULE_VIOLATION, failure.category());
    }

    /** Cursor thật + dữ liệu thật: lọc bận trước chia trang, không lặp/mất xe khi nhiều khoảng cách bằng nhau. */
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 5})
    void pagesAvailableVehiclesWithoutDuplicatesOrGaps(int limit) {
        var near = fixtures.branch(input.latitude + 0.001, input.longitude);
        var origin = fixtures.branch(input.latitude, input.longitude);
        var last = fixtures.active(near.code(), model);
        var busy = fixtures.active(origin.code(), model);
        var first = fixtures.active(origin.code(), model);
        var second = fixtures.active(origin.code(), model);
        var third = fixtures.active(origin.code(), model);
        availability.hold(busy.id(), new Period(input.start, input.end), Duration.ofHours(2), "busy-before-page");
        input.limit = limit;
        List<String> seen = new ArrayList<>();
        int pages = 0;
        do {
            assertTrue(++pages <= 4, "Cursor must terminate instead of repeating a page.");
            var page = search.search(input.build());
            seen.addAll(page.items().stream().map(SearchVehicleListItem::code).toList());
            if (page.nextCursor() != null) {
                assertEquals(limit, page.items().size(), "Busy candidates must not cause a short intermediate page.");
            }
            input.cursor = page.nextCursor();
        } while (input.cursor != null);
        assertEquals(List.of(first.code(), second.code(), third.code(), last.code()), seen);
    }

    /** So sánh đúng thứ tự và tập xe; kết quả rỗng cũng là một khẳng định tường minh. */
    private static void assertCodes(SearchVehiclesPage page, String... expected) {
        assertEquals(List.of(expected), page.items().stream().map(SearchVehicleListItem::code).toList());
    }
}
