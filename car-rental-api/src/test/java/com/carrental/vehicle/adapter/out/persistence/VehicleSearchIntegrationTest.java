package com.carrental.vehicle.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import com.carrental.shared.rental.RentalType;
import com.carrental.vehicle.api.VehicleSearchDirectory;
import com.carrental.vehicle.api.VehicleSearchView;
import com.carrental.vehicle.application.port.out.ReadVehiclePort;
import com.carrental.vehicle.application.port.out.WriteVehiclePort;
import com.carrental.vehicle.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm đường Directory → use case → SQL thật trên PostgreSQL cho BR-010/018/126/209.
 * Chi nhánh là tham chiếu logic không FK; dùng ID riêng để không lẫn fixture khác.
 * Mỗi test rollback, không chạm dữ liệu local và không giả lập semantics của SQL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@Transactional
class VehicleSearchIntegrationTest {
    private static final long BRANCH = 600_000_042L;
    private static final long OTHER_BRANCH = 600_000_043L;
    @Autowired private VehicleSearchDirectory directory;
    @Autowired private WriteVehiclePort writes;
    @Autowired private ReadVehiclePort reads;

    /** Chỉ trả ACTIVE đúng chi nhánh, đủ trường, giữ nguyên hãng/dòng và sắp ID ổn định. */
    @Test
    void scopesActiveVehiclesAndMapsAllFields() {
        insert(1, BRANCH, VehicleStatus.ACTIVE, 7, Transmission.MANUAL, FuelType.DIESEL, " Ford ", " Everest ");
        insert(2, BRANCH, VehicleStatus.DRAFT, 5, Transmission.AUTOMATIC, FuelType.PETROL, "Toyota", "Vios");
        insert(3, OTHER_BRANCH, VehicleStatus.ACTIVE, 5, Transmission.AUTOMATIC, FuelType.PETROL, "Toyota", "Vios");
        insert(4, BRANCH, VehicleStatus.ACTIVE, 4, Transmission.AUTOMATIC, FuelType.ELECTRIC, "VinFast", "VF 3");
        var result = list(null, null, null, null, null, null);
        assertEquals(List.of(code(1), code(4)), result.stream().map(VehicleSearchView::code).toList());
        long expectedId = reads.findByCode(code(1)).orElseThrow().id();
        assertEquals(new VehicleSearchView(expectedId, code(1), BRANCH, 7,
                "MANUAL", "DIESEL", " Ford ", " Everest ", true), result.getFirst());
        assertTrue(result.get(1).id() > expectedId);
        assertThrows(UnsupportedOperationException.class, result::clear);
    }

    /** Tất cả trạng thái khác ACTIVE đều không hiện, không chỉ riêng DRAFT. */
    @ParameterizedTest
    @EnumSource(value = VehicleStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "ACTIVE")
    void excludesEveryNonActiveStatus(VehicleStatus status) {
        insert(1, BRANCH, status, 5, Transmission.AUTOMATIC, FuelType.PETROL, "Toyota", "Vios");
        assertTrue(list(null, null, null, null, null, null).isEmpty());
    }

    /** Tập nhiều chi nhánh là hợp các phạm vi, ID lặp không nhân đôi xe. */
    @Test
    void acceptsMultipleBranchesWithoutDuplicateVehicles() {
        insert(1, BRANCH, VehicleStatus.ACTIVE, 5, Transmission.AUTOMATIC, FuelType.PETROL, "Toyota", "Vios");
        insert(2, OTHER_BRANCH, VehicleStatus.ACTIVE, 5, Transmission.AUTOMATIC, FuelType.PETROL, "Toyota", "Vios");
        var result = directory.list(List.of(BRANCH, OTHER_BRANCH, BRANCH), RentalType.DAILY,
                null, null, null, null, null, null);
        assertEquals(List.of(code(1), code(2)), result.stream().map(VehicleSearchView::code).toList());
    }

    /** Bốn số chỗ được lọc độc lập, không chỉ kiểm tất cả bộ lọc cùng lúc. */
    @ParameterizedTest
    @ValueSource(ints = {4, 5, 7, 16})
    void filtersSeats(int expected) {
        for (int seats : new int[]{4, 5, 7, 16}) {
            insert(seats, BRANCH, VehicleStatus.ACTIVE, seats, Transmission.AUTOMATIC, FuelType.PETROL, "Toyota", "Model");
        }
        assertCodes(list(expected, null, null, null, null, null), code(expected));
    }

    /** Cả hai hộp số đi qua bind và mapper thật. */
    @ParameterizedTest
    @EnumSource(Transmission.class)
    void filtersTransmission(Transmission expected) {
        int index = 0;
        for (Transmission value : Transmission.values()) {
            insert(++index, BRANCH, VehicleStatus.ACTIVE, 5, value, FuelType.PETROL, "Toyota", "Model");
        }
        var result = list(null, expected.name(), null, null, null, null);
        assertEquals(1, result.size());
        assertEquals(expected.name(), result.getFirst().transmission());
    }

    /** Bốn loại nhiên liệu được lọc đúng và không bị ánh xạ sang tên khác. */
    @ParameterizedTest
    @EnumSource(FuelType.class)
    void filtersFuelType(FuelType expected) {
        int index = 0;
        for (FuelType value : FuelType.values()) {
            insert(++index, BRANCH, VehicleStatus.ACTIVE, 5, Transmission.AUTOMATIC, value, "Toyota", "Model");
        }
        var result = list(null, null, expected.name(), null, null, null);
        assertEquals(1, result.size());
        assertEquals(expected.name(), result.getFirst().fuelType());
    }

    /** Hãng và dòng lọc độc lập: bỏ trắng hai đầu, không phân biệt hoa thường. */
    @Test
    void matchesMakeAndModelIndependentlyAfterTrimming() {
        insert(1, BRANCH, VehicleStatus.ACTIVE, 5, Transmission.AUTOMATIC, FuelType.PETROL, " \tToYoTa\n", "  ViOs\t");
        insert(2, BRANCH, VehicleStatus.ACTIVE, 5, Transmission.AUTOMATIC, FuelType.PETROL, "Honda", "Civic");
        assertCodes(list(null, null, null, " \ntoyota\t", null, null), code(1));
        assertCodes(list(null, null, null, null, " vIoS ", null), code(1));
        assertTrue(list(null, null, null, "Toy", null, null).isEmpty());
        assertTrue(list(null, null, null, null, "Vio", null).isEmpty());
    }

    /** Các bộ lọc kết hợp bằng AND; chỉ một thuộc tính lệch cũng loại ứng viên. */
    @Test
    void combinesEveryAttributeFilterWithAnd() {
        insert(1, BRANCH, VehicleStatus.ACTIVE, 5, Transmission.AUTOMATIC, FuelType.PETROL, "Toyota", "Vios");
        insert(2, BRANCH, VehicleStatus.ACTIVE, 7, Transmission.AUTOMATIC, FuelType.PETROL, "Toyota", "Vios");
        insert(3, BRANCH, VehicleStatus.ACTIVE, 5, Transmission.MANUAL, FuelType.PETROL, "Toyota", "Vios");
        insert(4, BRANCH, VehicleStatus.ACTIVE, 5, Transmission.AUTOMATIC, FuelType.DIESEL, "Toyota", "Vios");
        insert(5, BRANCH, VehicleStatus.ACTIVE, 5, Transmission.AUTOMATIC, FuelType.PETROL, "Honda", "Vios");
        insert(6, BRANCH, VehicleStatus.ACTIVE, 5, Transmission.AUTOMATIC, FuelType.PETROL, "Toyota", "Camry");
        assertCodes(list(5, "AUTOMATIC", "PETROL", "toyota", "vios", true), code(1));
    }

    /** %, dấu gạch dưới và nháy SQL là dữ liệu, không phải wildcard hoặc câu SQL. */
    @ParameterizedTest
    @ValueSource(strings = {"%", "_", "Toyota' OR '1'='1"})
    void treatsSpecialCharactersAsLiteralData(String value) {
        insert(1, BRANCH, VehicleStatus.ACTIVE, 5, Transmission.AUTOMATIC, FuelType.PETROL, value, value);
        insert(2, BRANCH, VehicleStatus.ACTIVE, 5, Transmission.AUTOMATIC, FuelType.PETROL, "Toyota", "Vios");
        assertCodes(list(null, null, null, value, null, null), code(1));
        assertCodes(list(null, null, null, null, value, null), code(1));
    }

    /** SQL và policy phối hợp đúng cho cả ba gói, đủ true/false/null của bộ lọc thế chấp. */
    @ParameterizedTest
    @CsvSource({"HOURLY,true,1", "DAILY,true,1", "DAILY,false,0",
            "MONTHLY,false,1", "MONTHLY,true,0", "MONTHLY,,1"})
    void appliesCollateralPolicyAfterReading(RentalType type, Boolean filter, int count) {
        insert(1, BRANCH, VehicleStatus.ACTIVE, 5, Transmission.AUTOMATIC, FuelType.PETROL, "Toyota", "Vios");
        assertEquals(count, directory.list(List.of(BRANCH), type,
                null, null, null, null, null, filter).size());
    }

    /** Tập chi nhánh rỗng và tập không có xe đều rỗng, không fallback ra toàn bộ bảng. */
    @Test
    void emptyAndMissingScopesReturnNothing() {
        insert(1, BRANCH, VehicleStatus.ACTIVE, 5, Transmission.AUTOMATIC, FuelType.PETROL, "Toyota", "Vios");
        assertTrue(directory.list(List.of(), RentalType.DAILY, null, null, null, null, null, null).isEmpty());
        assertTrue(directory.list(List.of(OTHER_BRANCH), RentalType.DAILY, null, null, null, null, null, null).isEmpty());
    }

    /**
     * Nếu dữ liệu PARTNER lọt vào phạm vi chi nhánh, SQL không âm thầm lọc nó theo sở hữu.
     * Fixture chủ động mô phỏng dữ liệu ngoài GĐ1 để chứng minh lỗi nội bộ từ resolver.
     */
    @Test
    void partnerCandidateFailsExplicitlyInsteadOfBeingHidden() {
        var vehicle = Vehicle.restore(code(1), "SEARCH-1", OwnershipType.PARTNER,
                FuelType.PETROL, BRANCH, VehicleStatus.ACTIVE,
                new VehicleDocuments(null, null), new VehicleSpecifications(5, Transmission.AUTOMATIC, "Toyota", "Vios"));
        assertTrue(writes.insert(vehicle));
        var error = assertThrowsExactly(IllegalStateException.class,
                () -> list(null, null, null, null, null, false));
        assertEquals("Collateral policy for PARTNER vehicles is not implemented.", error.getMessage());
    }

    /** Gọi cổng công khai, không nhảy trực tiếp xuống adapter để bỏ qua validation/policy. */
    private List<VehicleSearchView> list(Integer seats, String transmission, String fuel,
            String make, String model, Boolean collateral) {
        return directory.list(List.of(BRANCH), RentalType.DAILY, seats, transmission, fuel, make, model, collateral);
    }

    /** Tạo fixture bằng cổng ghi thật; giấy tờ cũ chứng minh đọc danh mục không kiểm lại cổng duyệt. */
    private void insert(int index, long branch, VehicleStatus status, int seats, Transmission transmission,
            FuelType fuel, String make, String model) {
        var vehicle = Vehicle.restore(code(index), "SEARCH-" + index, OwnershipType.COMPANY,
                fuel, branch, status, new VehicleDocuments(LocalDate.of(2000, 1, 1), LocalDate.of(2000, 1, 1)),
                new VehicleSpecifications(seats, transmission, make, model));
        assertTrue(writes.insert(vehicle));
    }

    /** Mã fixture đủ sáu ký tự và không trùng giữa các xe trong cùng test. */
    private String code(int index) {
        return "XE-S%05d".formatted(index);
    }

    /** So sánh đúng tập và thứ tự mã, không chỉ kiểm có kết quả. */
    private void assertCodes(List<VehicleSearchView> result, String... expected) {
        assertEquals(List.of(expected), result.stream().map(VehicleSearchView::code).toList());
    }
}
