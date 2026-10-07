package com.carrental.vehicle.application.service;

import com.carrental.shared.rental.RentalType;
import com.carrental.vehicle.application.port.out.ReadVehiclePort;
import com.carrental.vehicle.application.query.ListSearchVehiclesQuery;
import com.carrental.vehicle.application.view.VehicleDetail;
import com.carrental.vehicle.application.view.VehicleSearchCandidate;
import com.carrental.vehicle.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

/** Kiểm điều phối BR-110/126/209, không thay chính sách bằng phép lọc sở hữu. */
class VehicleSearchQueryServiceTest {
    /** Truy vấn theo lô đúng một lần và trả view bất biến, giữ nguyên dữ liệu hiển thị. */
    @Test
    void readsOnceAndMapsWithoutOwnership() {
        var query = query(RentalType.DAILY, null);
        var port = new SearchReadStub(List.of(candidate(1, OwnershipType.COMPANY),
                candidate(2, OwnershipType.COMPANY)));
        var result = service(port).list(query);
        assertEquals(1, port.calls);
        assertSame(query, port.lastQuery);
        assertEquals(2, result.size());
        var first = result.getFirst();
        assertEquals(1L, first.id());
        assertEquals("XE-T60001", first.code());
        assertEquals(42L, first.branchId());
        assertEquals(5, first.seats());
        assertEquals("AUTOMATIC", first.transmission());
        assertEquals("PETROL", first.fuelType());
        assertEquals(" Toyota ", first.make());
        assertEquals(" Vios ", first.model());
        assertTrue(first.collateralFree());
        assertThrows(UnsupportedOperationException.class, result::clear);
    }

    /** Cờ true/false lọc theo chính sách; null không lọc. */
    @ParameterizedTest
    @CsvSource({"DAILY,true,1", "DAILY,false,0", "HOURLY,true,1",
            "MONTHLY,true,0", "MONTHLY,false,1", "MONTHLY,,1"})
    void filtersResolvedCollateral(RentalType type, Boolean filter, int count) {
        var port = new SearchReadStub(List.of(candidate(1, OwnershipType.COMPANY)));
        var result = service(port).list(query(type, filter));
        assertEquals(count, result.size());
        if (!result.isEmpty() && filter != null) {
            assertEquals(filter.booleanValue(), result.getFirst().collateralFree());
        }
    }

    /** Không có chi nhánh thì không gọi persistence kể cả bộ lọc thế chấp đang bật. */
    @Test
    void emptyBranchesNeverReadDatabase() {
        var port = new SearchReadStub(List.of(candidate(1, OwnershipType.COMPANY)));
        assertTrue(service(port).list(ListSearchVehiclesQuery.from(List.of(),
                RentalType.DAILY, null, null, null, null, null, true)).isEmpty());
        assertEquals(0, port.calls);
    }

    /** PARTNER không bị âm thầm bỏ qua, kể cả khi lọc miễn hoặc bắt buộc thế chấp. */
    @Test
    void partnerAlwaysFailsBeforeCollateralFilter() {
        for (Boolean filter : java.util.Arrays.asList(null, true, false)) {
            var port = new SearchReadStub(List.of(candidate(1, OwnershipType.COMPANY),
                    candidate(2, OwnershipType.PARTNER)));
            var error = assertThrowsExactly(IllegalStateException.class,
                    () -> service(port).list(query(RentalType.DAILY, filter)));
            assertEquals("Collateral policy for PARTNER vehicles is not implemented.", error.getMessage());
        }
    }

    /** Lỗi hạ tầng không được đổi thành kết quả rỗng. */
    @Test
    void propagatesReadFailure() {
        var failure = new IllegalStateException("Database read failed.");
        var port = new SearchReadStub(List.of());
        port.failure = failure;
        assertSame(failure, assertThrowsExactly(IllegalStateException.class,
                () -> service(port).list(query(RentalType.DAILY, null))));
    }

    /** Tạo query dùng cùng tập chi nhánh cho các kịch bản chính sách. */
    private ListSearchVehiclesQuery query(RentalType type, Boolean filter) {
        return ListSearchVehiclesQuery.from(List.of(42L), type, null,
                null, null, null, null, filter);
    }

    /** Tạo read model nội bộ, không mở API tạo xe đối tác. */
    private VehicleSearchCandidate candidate(long id, OwnershipType ownership) {
        return new VehicleSearchCandidate(id, "XE-T6000" + id, 42L, ownership,
                5, Transmission.AUTOMATIC, FuelType.PETROL, " Toyota ", " Vios ");
    }

    /** Lắp use case bằng resolver thật, không mock ma trận nghiệp vụ. */
    private VehicleSearchQueryService service(SearchReadStub port) {
        return new VehicleSearchQueryService(port, new CollateralPolicyResolver());
    }

    /** Cổng giả chỉ cho phép truy vấn theo lô, giúp bắt truy vấn N+1 ngoài ý muốn. */
    private static final class SearchReadStub implements ReadVehiclePort {
        private final List<VehicleSearchCandidate> candidates;
        private int calls;
        private ListSearchVehiclesQuery lastQuery;
        private RuntimeException failure;

        /** Nhận tập ứng viên tách biệt với kết quả đã phân giải. */
        private SearchReadStub(List<VehicleSearchCandidate> candidates) {
            this.candidates = candidates;
        }

        /** Ghi lại số lượt đọc và query, có thể mô phỏng lỗi hạ tầng. */
        @Override
        public List<VehicleSearchCandidate> findSearchCandidates(ListSearchVehiclesQuery query) {
            calls++;
            lastQuery = query;
            if (failure != null) {
                throw failure;
            }
            return candidates;
        }

        /** Không cho use case danh sách đọc từng view. */
        @Override
        public Optional<VehicleDetail> findByCode(String code) {
            throw new AssertionError("Per-vehicle reads are not allowed.");
        }

        /** Không cho đường tìm kiếm tải aggregate dùng cho ghi. */
        @Override
        public Optional<Vehicle> loadAggregate(String code) {
            throw new AssertionError("Aggregate reads are not allowed.");
        }
    }
}
