package com.carrental.branch.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import com.carrental.branch.api.BranchDirectory;
import com.carrental.branch.api.BranchSearchView;
import com.carrental.branch.application.port.out.WriteBranchPort;
import com.carrental.branch.domain.Branch;
import com.carrental.branch.domain.BranchLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm Directory → adapter → use case → SQL production với PostgreSQL/PostGIS thật.
 * Mỗi test rollback dữ liệu riêng; không thay bằng SQL mô phỏng công thức khoảng cách.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@Transactional
class BranchNearbyIntegrationTest {
    private static final double LATITUDE = 10.762622;
    private static final double LONGITUDE = 106.660172;

    @Autowired
    private WriteBranchPort writes;

    @Autowired
    private BranchDirectory directory;

    /** BR-003/808: trả đủ dữ liệu hiển thị, khoảng cách mét và đúng thứ tự gần trước. */
    @Test
    void returnsOnlyNearbyBranchesWithDisplayFieldsAndMeterDistances() {
        insert("CN-NEAR02", LATITUDE + 0.001, LONGITUDE, " Near Branch ", " 456 Street ");
        insert("CN-NEAR01", LATITUDE, LONGITUDE, "Origin Branch", "123 Street");
        insert("CN-FAR001", LATITUDE + 1, LONGITUDE, "Far Branch", "789 Street");
        List<BranchSearchView> result = directory.findWithinRadius(LATITUDE, LONGITUDE, 200.0);
        assertEquals(List.of("CN-NEAR01", "CN-NEAR02"), result.stream().map(BranchSearchView::code).toList());
        BranchSearchView origin = result.get(0);
        BranchSearchView near = result.get(1);
        assertTrue(origin.id() > 0);
        assertEquals("Origin Branch", origin.name());
        assertEquals("123 Street", origin.address());
        assertEquals(0.0, origin.distanceMeters(), 0.000001);
        assertEquals(" Near Branch ", near.name());
        assertEquals(" 456 Street ", near.address());
        assertTrue(near.distanceMeters() > 100 && near.distanceMeters() < 120,
                "A 0.001-degree latitude offset must be roughly 111 meters, not kilometers.");
        assertThrows(UnsupportedOperationException.class, result::clear);
    }

    /** Bán kính nhỏ hơn hoặc lớn hơn khoảng cách thật phải thay đổi đúng tập ứng viên. */
    @ParameterizedTest
    @CsvSource({"50.0,1", "200.0,2", "200000.0,3"})
    void filtersByRadiusInMeters(double radius, int expectedSize) {
        insert("CN-RAD001", LATITUDE, LONGITUDE, "Origin", "Street 1");
        insert("CN-RAD002", LATITUDE + 0.001, LONGITUDE, "Near", "Street 2");
        insert("CN-RAD003", LATITUDE + 1, LONGITUDE, "Far", "Street 3");
        assertEquals(expectedSize, directory.findWithinRadius(LATITUDE, LONGITUDE, radius).size());
    }

    /** Hai chi nhánh cùng vị trí được xếp theo ID để thứ tự không phụ thuộc kế hoạch thực thi. */
    @Test
    void ordersEqualDistancesById() {
        insert("CN-TIE002", LATITUDE, LONGITUDE, "First", "Street 1");
        insert("CN-TIE001", LATITUDE, LONGITUDE, "Second", "Street 2");
        List<BranchSearchView> result = directory.findWithinRadius(LATITUDE, LONGITUDE, 1.0);
        assertEquals(List.of("CN-TIE002", "CN-TIE001"), result.stream().map(BranchSearchView::code).toList());
        assertTrue(result.get(0).id() < result.get(1).id());
    }

    /** Geography nhận ra hai điểm hai bên kinh tuyến 180 độ vẫn gần nhau. */
    @Test
    void handlesAntimeridianUsingGeography() {
        insert("CN-DATE01", 0, -179.999, "Across Date Line", "Street");
        List<BranchSearchView> result = directory.findWithinRadius(0.0, 179.999, 300.0);
        assertEquals(List.of("CN-DATE01"), result.stream().map(BranchSearchView::code).toList());
        assertTrue(result.getFirst().distanceMeters() > 200 && result.getFirst().distanceMeters() < 250);
    }

    /** Không có ứng viên trả danh sách rỗng, không lỗi hay fallback sang tất cả chi nhánh. */
    @Test
    void returnsEmptyOutsideAllBranchLocations() {
        insert("CN-NONE01", LATITUDE, LONGITUDE, "Far Away", "Street");
        assertEquals(List.of(), directory.findWithinRadius(-80.0, -120.0, 1.0));
    }

    /** Chèn fixture bằng cổng ghi thật, khẳng định setup thành công trước khi kiểm truy vấn. */
    private void insert(String code, double latitude, double longitude, String name, String address) {
        assertTrue(writes.insert(new Branch(code, new BranchLocation(latitude, longitude), name, address)));
    }
}
