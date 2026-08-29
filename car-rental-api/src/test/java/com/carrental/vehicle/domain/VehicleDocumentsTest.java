package com.carrental.vehicle.domain;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

/**
 * Kiểm tra điều kiện giấy tờ tại thời điểm duyệt xe theo BR-005.
 *
 * <p>Ngày hết hạn được hiểu theo BR-015:
 * giấy tờ hết hạn ngày D không còn hợp lệ từ ngày D.
 *
 * <p>Ngày duyệt được cố định để kết quả không phụ thuộc
 * ngày chạy test hoặc đồng hồ của máy.
 *
 * <p>Test chỉ kiểm domain thuần Java,
 * không khởi động Spring hoặc kết nối cơ sở dữ liệu.
 */
class VehicleDocumentsTest {

    private static final LocalDate APPROVAL_DATE =
            LocalDate.of(2026, 9, 20);

    /**
     * Chứng minh giấy tờ được chấp nhận khi cả hai hạn đều sau ngày duyệt.
     *
     * <p>Việc kiểm tra không thay đổi các ngày đã được lưu trong đối tượng.
     *
     * @param inspectionDays số ngày từ ngày duyệt đến hạn đăng kiểm
     * @param insuranceDays số ngày từ ngày duyệt đến hạn bảo hiểm TNDS
     */
    @ParameterizedTest
    @CsvSource({
            "1, 1",
            "1, 30",
            "30, 1"
    })
    void acceptsDocumentsExpiringAfterApprovalDate(
            int inspectionDays,
            int insuranceDays
    ) {
        LocalDate inspectionExpiresOn =
                APPROVAL_DATE.plusDays(inspectionDays);
        LocalDate liabilityInsuranceExpiresOn =
                APPROVAL_DATE.plusDays(insuranceDays);

        VehicleDocuments documents = new VehicleDocuments(
                inspectionExpiresOn,
                liabilityInsuranceExpiresOn
        );

        assertDoesNotThrow(
                () -> documents.validateForApproval(APPROVAL_DATE)
        );

        assertEquals(
                inspectionExpiresOn,
                documents.inspectionExpiresOn()
        );
        assertEquals(
                liabilityInsuranceExpiresOn,
                documents.liabilityInsuranceExpiresOn()
        );
    }

    /**
     * Cung cấp các hồ sơ thiếu một hoặc cả hai ngày hết hạn.
     *
     * <p>Có cả trường hợp giấy tờ còn lại đã hết hạn,
     * nhằm kiểm thứ tự ưu tiên báo thiếu giấy tờ.
     *
     * @return các cặp ngày có ít nhất một giá trị null
     */
    private static Stream<Arguments> missingDocumentDates() {
        LocalDate futureDate = APPROVAL_DATE.plusDays(1);
        LocalDate pastDate = APPROVAL_DATE.minusDays(1);

        return Stream.of(
                Arguments.of(null, null),
                Arguments.of(null, futureDate),
                Arguments.of(futureDate, null),
                Arguments.of(null, pastDate),
                Arguments.of(pastDate, null)
        );
    }

    /**
     * Chứng minh hồ sơ thiếu giấy tờ vẫn biểu diễn được,
     * nhưng không được phép vượt qua bước kiểm tra khi duyệt.
     *
     * <p>Đối tượng được tạo bên ngoài phần chờ exception,
     * nên test cũng phát hiện việc kiểm thiếu giấy tờ
     * bị chuyển nhầm vào constructor.
     *
     * @param inspectionExpiresOn hạn đăng kiểm, có thể null
     * @param liabilityInsuranceExpiresOn hạn bảo hiểm TNDS, có thể null
     */
    @ParameterizedTest
    @MethodSource("missingDocumentDates")
    void rejectsMissingDocumentsAtApproval(
            LocalDate inspectionExpiresOn,
            LocalDate liabilityInsuranceExpiresOn
    ) {
        VehicleDocuments documents = new VehicleDocuments(
                inspectionExpiresOn,
                liabilityInsuranceExpiresOn
        );

        assertRuleViolation(
                documents,
                ErrorCode.VEHICLE_DOCUMENT_MISSING
        );
    }

    /**
     * Chứng minh giấy tờ hết hạn trước hoặc đúng ngày duyệt bị từ chối.
     *
     * <p>Kiểm tất cả tổ hợp hôm trước, cùng ngày và hôm sau,
     * trừ trường hợp cả hai cùng ở hôm sau vì đó là hồ sơ hợp lệ.
     *
     * <p>Hồ sơ hết hạn vẫn phải khởi tạo được.
     * Lỗi chỉ phát sinh khi kiểm điều kiện duyệt.
     *
     * @param inspectionDays độ lệch ngày của hạn đăng kiểm
     * @param insuranceDays độ lệch ngày của hạn bảo hiểm TNDS
     */
    @ParameterizedTest
    @CsvSource({
            "-1, -1",
            "-1, 0",
            "-1, 1",
            "0, -1",
            "0, 0",
            "0, 1",
            "1, -1",
            "1, 0"
    })
    void rejectsExpiredDocumentsAtApproval(
            int inspectionDays,
            int insuranceDays
    ) {
        VehicleDocuments documents = new VehicleDocuments(
                APPROVAL_DATE.plusDays(inspectionDays),
                APPROVAL_DATE.plusDays(insuranceDays)
        );

        assertRuleViolation(
                documents,
                ErrorCode.VEHICLE_DOCUMENT_EXPIRED
        );
    }

    /**
     * Chứng minh thiếu ngày duyệt là lỗi lập trình của tầng gọi.
     *
     * <p>Domain không tự thay ngày thiếu bằng ngày hiện tại
     * và không báo nhầm thành lỗi giấy tờ của xe.
     */
    @Test
    void rejectsMissingApprovalDate() {
        VehicleDocuments documents = new VehicleDocuments(
                APPROVAL_DATE.plusDays(1),
                APPROVAL_DATE.plusDays(1)
        );

        NullPointerException failure = assertThrowsExactly(
                NullPointerException.class,
                () -> documents.validateForApproval(null)
        );

        assertEquals(
                "approvalDate must not be null.",
                failure.getMessage()
        );
    }

    /**
     * Kiểm việc duyệt bị từ chối với đúng exception và thông tin lỗi.
     *
     * @param documents hồ sơ cần kiểm tại ngày duyệt cố định
     * @param expectedCode mã lỗi nghiệp vụ mong đợi
     */
    private static void assertRuleViolation(
            VehicleDocuments documents,
            ErrorCode expectedCode
    ) {
        DomainException failure = assertThrowsExactly(
                DomainException.class,
                () -> documents.validateForApproval(APPROVAL_DATE)
        );

        assertEquals(expectedCode, failure.errorCode());
        assertEquals(
                DomainException.Category.RULE_VIOLATION,
                failure.category()
        );
        assertEquals(
                expectedCode.defaultMessage(),
                failure.getMessage()
        );
    }
}