package com.carrental.branch.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import com.carrental.branch.application.port.out.ReadBranchPort;
import com.carrental.branch.application.port.out.WriteBranchPort;
import com.carrental.branch.application.view.BranchDetail;
import com.carrental.branch.domain.Branch;
import com.carrental.branch.domain.BranchLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kiểm tra các persistence adapter chi nhánh với PostgreSQL và PostGIS thật.
 *
 * <p>Kiểm chứng việc lưu vị trí phục vụ BR-003 và xử lý mã nghiệp vụ
 * duy nhất theo database-guideline mục 2.
 *
 * <p>Các port được gọi trực tiếp trên luồng test.
 * Mỗi lần chạy test có transaction riêng và được rollback sau khi kết thúc.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@Transactional
class BranchPersistenceIntegrationTest {

    @Autowired
    private ReadBranchPort readBranchPort;

    @Autowired
    private WriteBranchPort writeBranchPort;

    /**
     * Chứng minh chi nhánh được chèn và đọc lại đúng mã, vĩ độ và kinh độ.
     *
     * <p>Dùng tọa độ bất đối xứng để phát hiện việc đảo hai trục,
     * đồng thời kiểm tra giá trị bằng không và vĩ độ âm.
     *
     * @param latitude vĩ độ cần lưu
     * @param longitude kinh độ cần lưu
     */
    @ParameterizedTest
    @CsvSource({
            "10.762622, 106.660172",
            "0.0, 0.0",
            "-33.8688, 151.2093"
    })
    void insertsAndReadsBranchCoordinates(
            double latitude,
            double longitude
    ) {
        Branch branch = new Branch(
                "CN-READ01",
                new BranchLocation(latitude, longitude)
        );

        assertTrue(writeBranchPort.insert(branch));

        BranchDetail detail = readRequiredBranch(branch.code());

        assertTrue(detail.id() > 0);
        assertEquals(branch.code(), detail.code());
        assertEquals(latitude, detail.latitude());
        assertEquals(longitude, detail.longitude());
    }

    /**
     * Chứng minh cổng đọc trả kết quả rỗng khi mã chi nhánh không tồn tại.
     */
    @Test
    void returnsEmptyWhenBranchDoesNotExist() {
        Optional<BranchDetail> result =
                readBranchPort.findByCode("CN-MISS01");

        assertTrue(result.isEmpty());
    }

    /**
     * Chứng minh trùng mã không ghi đè dữ liệu cũ hoặc làm hỏng transaction.
     *
     * <p>Sau lần chèn bị bỏ qua, tiếp tục chèn và đọc một chi nhánh khác
     * ngay trong cùng transaction.
     */
    @Test
    void rejectsDuplicateCodeWithoutChangingExistingBranchOrAbortingTransaction() {
        Branch original = new Branch(
                "CN-DUP001",
                new BranchLocation(10.762622, 106.660172)
        );

        assertTrue(writeBranchPort.insert(original));

        BranchDetail originalDetail =
                readRequiredBranch(original.code());

        Branch conflicting = new Branch(
                original.code(),
                new BranchLocation(21.028511, 105.804817)
        );

        assertFalse(writeBranchPort.insert(conflicting));

        BranchDetail detailAfterConflict =
                readRequiredBranch(original.code());

        assertEquals(originalDetail, detailAfterConflict);

        Branch another = new Branch(
                "CN-NEXT01",
                conflicting.location()
        );

        assertTrue(writeBranchPort.insert(another));

        BranchDetail anotherDetail =
                readRequiredBranch(another.code());

        assertEquals(another.code(), anotherDetail.code());
        assertEquals(
                another.location().latitude(),
                anotherDetail.latitude()
        );
        assertEquals(
                another.location().longitude(),
                anotherDetail.longitude()
        );
    }

    /**
     * Đọc chi nhánh bắt buộc phải tồn tại trong kịch bản test.
     *
     * <p>Kiểm tra sự tồn tại trước khi lấy view để thông báo thất bại
     * chỉ rõ mã chi nhánh đang thiếu.
     *
     * @param code mã chi nhánh đã được kịch bản test tạo
     * @return thông tin chi tiết của chi nhánh tìm được
     */
    private BranchDetail readRequiredBranch(String code) {
        Optional<BranchDetail> result = readBranchPort.findByCode(code);

        assertTrue(
                result.isPresent(),
                "Expected branch to exist: " + code
        );

        return result.orElseThrow();
    }
}