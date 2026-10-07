package com.carrental.search.application.service;

import com.carrental.availability.api.AvailabilityDirectory;
import com.carrental.availability.api.Period;
import com.carrental.booking.api.RentalTerms;
import com.carrental.booking.api.RentalTermsDirectory;
import com.carrental.branch.api.BranchDirectory;
import com.carrental.branch.api.BranchSearchView;
import com.carrental.search.domain.*;
import com.carrental.search.application.view.SearchVehicleListItem;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.rental.*;
import com.carrental.vehicle.api.VehicleSearchDirectory;
import com.carrental.vehicle.api.VehicleSearchView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import static com.carrental.search.SearchTestQueries.input;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Kiểm điều phối BR-104/109/110/112/116/125/126, không giả làm bằng chứng tranh chấp PostgreSQL. */
class SearchVehiclesQueryServiceTest {
    private RentalTermsDirectory terms;
    private BranchDirectory branches;
    private VehicleSearchDirectory vehicles;
    private AvailabilityDirectory availability;
    private VehicleSearchQueryService service;

    /** Mỗi test có directory giả riêng; policy của search vẫn dùng implementation thật. */
    @BeforeEach
    void setUp() {
        terms = mock(RentalTermsDirectory.class);
        branches = mock(BranchDirectory.class);
        vehicles = mock(VehicleSearchDirectory.class);
        availability = mock(AvailabilityDirectory.class);
        service = new VehicleSearchQueryService(terms, branches, vehicles, availability,
                new SearchRadiusSettings(10, 30), new SearchSupportPolicyResolver());
        when(terms.getTerms(any(), any(), any(), any())).thenReturn(rentalTerms(2));
        when(branches.findWithinRadius(anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of(
                branch(1, 100), branch(2, 200)));
        when(vehicles.list(anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(vehicle(3, 2), vehicle(2, 1), vehicle(1, 1)));
        when(availability.findBusyVehicleIds(any(), any(), anyCollection())).thenReturn(Set.of(2L));
    }

    /** Kiểm thứ tự bốn bước, km đổi mét, truyền nguyên bộ lọc và trả đúng xe/chi nhánh/cờ thế chấp. */
    @Test
    void validatesThenReadsDirectoriesOnceAndMapsResults() {
        var values = input();
        values.filters = new VehicleSearchFilters(5, "AUTOMATIC", "PETROL", " Toyota ", " Vios ", true);
        var query = values.build();
        var page = service.search(query);
        assertEquals(List.of("XE-000001", "XE-000003"), page.items().stream().map(SearchVehicleListItem::code).toList());
        var first = page.items().getFirst();
        assertEquals(1L, first.vehicleId());
        assertEquals("Toyota", first.make());
        assertEquals("Vios", first.model());
        assertEquals(5, first.seats());
        assertEquals("AUTOMATIC", first.transmission());
        assertEquals("PETROL", first.fuelType());
        assertTrue(first.collateralFree());
        assertEquals("CN-000001", first.branch().code());
        assertEquals("Branch 1", first.branch().name());
        assertEquals("Address 1", first.branch().address());
        assertEquals(100.0, first.distanceMeters());
        assertNull(page.nextCursor());
        assertThrows(UnsupportedOperationException.class, () -> page.items().clear());

        var order = inOrder(terms, branches, vehicles, availability);
        order.verify(terms).getTerms(RentalType.DAILY, PickupMethod.BRANCH, query.startInclusive(), query.endExclusive());
        order.verify(branches).findWithinRadius(query.latitude(), query.longitude(), 10_000.0);
        order.verify(vehicles).list(List.of(1L, 2L), RentalType.DAILY, 5, "AUTOMATIC", "PETROL", "Toyota", "Vios", true);
        order.verify(availability).findBusyVehicleIds(new Period(query.startInclusive(), query.endExclusive()),
                Duration.ofHours(2), List.of(3L, 2L, 1L));
        verifyNoMoreInteractions(terms, branches, vehicles, availability);
    }

    /** Từng gói nhận đệm từ booking và truyền riêng; search không tự cộng hay viết cứng đệm. */
    @ParameterizedTest
    @CsvSource({"HOURLY,1", "DAILY,2"})
    void passesUnbufferedPeriodAndResolvedBuffer(RentalType type, int hours) {
        var values = input();
        values.rentalType = type;
        when(terms.getTerms(any(), any(), any(), any())).thenReturn(rentalTerms(hours));
        service.search(values.build());
        verify(availability).findBusyVehicleIds(new Period(values.start, values.end),
                Duration.ofHours(hours), List.of(3L, 2L, 1L));
    }

    /** Vi phạm tối thiểu, giờ chi nhánh hoặc cửa sổ đặt được truyền đúng trước mọi truy vấn dữ liệu. */
    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {
            "RENTAL_DURATION_TOO_SHORT", "OUTSIDE_BRANCH_HOURS", "BOOKING_WINDOW_VIOLATION"})
    void propagatesRentalTermsFailureBeforeReadingCandidates(ErrorCode code) {
        var failure = DomainException.ruleViolation(code);
        when(terms.getTerms(any(), any(), any(), any())).thenThrow(failure);
        assertSame(failure, assertThrowsExactly(DomainException.class, () -> service.search(input().build())));
        verifyNoInteractions(branches, vehicles, availability);
    }

    /** Không có chi nhánh vẫn kiểm điều kiện thuê, nhưng không đọc xe/lịch. */
    @Test
    void emptyBranchesSkipVehicleAndAvailabilityReads() {
        when(branches.findWithinRadius(anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of());
        var page = service.search(input().build());
        assertTrue(page.items().isEmpty());
        assertNull(page.nextCursor());
        verify(terms).getTerms(any(), any(), any(), any());
        verifyNoInteractions(vehicles, availability);
    }

    /** Có chi nhánh nhưng không có xe thì không phát truy vấn lịch với tập rỗng. */
    @Test
    void emptyVehiclesSkipAvailabilityRead() {
        when(vehicles.list(anyList(), any(), any(), any(), any(), any(), any(), any())).thenReturn(List.of());
        var page = service.search(input().build());
        assertTrue(page.items().isEmpty());
        assertNull(page.nextCursor());
        verifyNoInteractions(availability);
    }

    /** Tất cả xe bận trả trang rỗng, không gán mã lỗi VEHICLE_NOT_AVAILABLE cho thao tác chỉ đọc. */
    @Test
    void allBusyReturnsEmptyPage() {
        when(availability.findBusyVehicleIds(any(), any(), anyCollection())).thenReturn(Set.of(1L, 2L, 3L));
        var page = service.search(input().build());
        assertTrue(page.items().isEmpty());
        assertNull(page.nextCursor());
    }

    /** Lựa chọn chưa hỗ trợ bị chặn trước cả điều kiện thuê và dữ liệu, không mặc định về lựa chọn khác. */
    @ParameterizedTest
    @CsvSource({
            "MONTHLY,SELF_DRIVE,BRANCH,NEAREST,SEARCH_RENTAL_TYPE_NOT_SUPPORTED",
            "DAILY,WITH_DRIVER,BRANCH,NEAREST,SEARCH_DRIVE_MODE_NOT_SUPPORTED",
            "DAILY,SELF_DRIVE,DELIVERY,NEAREST,SEARCH_PICKUP_METHOD_NOT_SUPPORTED",
            "DAILY,SELF_DRIVE,BRANCH,PRICE_ASC,SEARCH_SORT_NOT_SUPPORTED"
    })
    void rejectsUnsupportedOptionsBeforeDirectories(RentalType type, DriveMode drive,
            PickupMethod pickup, SearchSort sort, ErrorCode expected) {
        var values = input();
        values.rentalType = type;
        values.driveMode = drive;
        values.pickupMethod = pickup;
        values.sort = sort;
        assertEquals(expected, assertThrowsExactly(DomainException.class,
                () -> service.search(values.build())).errorCode());
        verifyNoInteractions(terms, branches, vehicles, availability);
    }

    /** Bán kính vượt trần và cursor hỏng không chạm directory. */
    @Test
    void rejectsRadiusAndBadCursorBeforeDirectories() {
        var values = input();
        values.radiusKm = 31.0;
        assertEquals(ErrorCode.INVALID_REQUEST, assertThrowsExactly(DomainException.class,
                () -> service.search(values.build())).errorCode());
        values.radiusKm = null;
        values.cursor = "broken";
        assertEquals(ErrorCode.INVALID_REQUEST, assertThrowsExactly(DomainException.class,
                () -> service.search(values.build())).errorCode());
        verifyNoInteractions(terms, branches, vehicles, availability);
    }

    /** Giữ đúng bán kính khách gửi hoặc mặc định từ settings, không đóng cứng 10 km trong service. */
    @Test
    void usesConfiguredAndExplicitRadius() {
        service = new VehicleSearchQueryService(terms, branches, vehicles, availability,
                new SearchRadiusSettings(5, 30), new SearchSupportPolicyResolver());
        var values = input();
        service.search(values.build());
        verify(branches).findWithinRadius(values.latitude, values.longitude, 5_000.0);
        values.radiusKm = 7.5;
        service.search(values.build());
        verify(branches).findWithinRadius(values.latitude, values.longitude, 7_500.0);
    }

    /** Ba trang nối đúng thứ tự, xe bận bị loại trước chia trang và khoảng cách sát nhau không làm tròn. */
    @Test
    void paginatesWithoutDuplicatesOrGapsForStableData() {
        preparePaging();
        var values = input();
        values.limit = 2;
        var first = service.search(values.build());
        assertIds(first.items(), 2L, 3L);
        assertNotNull(first.nextCursor());
        values.cursor = first.nextCursor();
        var second = service.search(values.build());
        assertIds(second.items(), 5L, 6L);
        assertNotNull(second.nextCursor());
        values.cursor = second.nextCursor();
        var third = service.search(values.build());
        assertIds(third.items(), 8L);
        assertNull(third.nextCursor());
    }

    /** Không cần tìm lại bản ghi neo; xe neo biến mất không khiến trang sau lùi hoặc bị lỗi. */
    @Test
    void cursorSurvivesAnchorDisappearingAndPageSizeChange() {
        preparePaging();
        var values = input();
        values.limit = 2;
        String cursor = service.search(values.build()).nextCursor();
        when(vehicles.list(anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(vehicle(8, 3), vehicle(6, 2), vehicle(5, 1), vehicle(1, 1), vehicle(2, 1)));
        when(availability.findBusyVehicleIds(any(), any(), anyCollection())).thenReturn(Set.of());
        values.cursor = cursor;
        values.limit = 3;
        var next = service.search(values.build());
        assertIds(next.items(), 5L, 6L, 8L);
        assertNull(next.nextCursor());
    }

    /** Lỗi từ nguồn dữ liệu không được hiểu là xe rảnh hay kết quả rỗng. */
    @Test
    void propagatesAvailabilityFailure() {
        var failure = new IllegalStateException("Availability read failed.");
        when(availability.findBusyVehicleIds(any(), any(), anyCollection())).thenThrow(failure);
        assertSame(failure, assertThrowsExactly(IllegalStateException.class, () -> service.search(input().build())));
    }

    /** Sai hợp đồng chi nhánh trả lỗi nội bộ thay vì âm thầm gắn xe vào chi nhánh bất kỳ. */
    @Test
    void rejectsVehicleOutsideRequestedBranches() {
        when(vehicles.list(anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(vehicle(1, 99)));
        assertThrowsExactly(IllegalStateException.class, () -> service.search(input().build()));
    }

    /** Chuẩn bị dữ liệu cố ý đảo thứ tự, có xe bận phía trước và hai khoảng cách gần như nhau. */
    private void preparePaging() {
        when(branches.findWithinRadius(anyDouble(), anyDouble(), anyDouble())).thenReturn(
                List.of(branch(3, 11), branch(2, Math.nextUp(10.0)), branch(1, 10)));
        when(vehicles.list(anyList(), any(), any(), any(), any(), any(), any(), any())).thenReturn(
                List.of(vehicle(8, 3), vehicle(6, 2), vehicle(5, 1), vehicle(3, 1),
                        vehicle(1, 1), vehicle(2, 1), vehicle(4, 2)));
        when(availability.findBusyVehicleIds(any(), any(), anyCollection())).thenReturn(Set.of(1L, 4L));
    }

    /** Điều kiện mẫu chỉ dành cho kiểm điều phối, không thay test policy thật của booking. */
    private RentalTerms rentalTerms(int bufferHours) {
        return new RentalTerms(Duration.ofHours(bufferHours), Optional.empty(), LocalTime.of(6, 0),
                LocalTime.of(23, 0), Duration.ofHours(1), java.time.Period.ofMonths(6));
    }

    /** Tạo hợp đồng chi nhánh với khoảng cách có thể kiểm chính xác. */
    private BranchSearchView branch(long id, double distance) {
        return new BranchSearchView(id, "CN-%06d".formatted(id), "Branch " + id, "Address " + id, distance);
    }

    /** Tạo hợp đồng xe không có loại sở hữu, đúng ranh giới R10. */
    private VehicleSearchView vehicle(long id, long branchId) {
        return new VehicleSearchView(id, "XE-%06d".formatted(id), branchId,
                5, "AUTOMATIC", "PETROL", "Toyota", "Vios", true);
    }

    /** So sánh cả thứ tự và tập ID, không chỉ đếm số kết quả. */
    private void assertIds(List<SearchVehicleListItem> items, Long... expected) {
        assertEquals(List.of(expected), items.stream().map(SearchVehicleListItem::vehicleId).toList());
    }
}
