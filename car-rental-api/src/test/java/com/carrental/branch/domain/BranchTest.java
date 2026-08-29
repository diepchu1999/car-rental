package com.carrental.branch.domain;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Kiểm tra danh tính và vị trí bắt buộc của chi nhánh.
 *
 * <p>Mã chi nhánh tuân theo database-guideline mục 2.
 * Vị trí phục vụ BR-003 và đã được BranchLocation kiểm tra.
 *
 * <p>Test chạy trực tiếp trên domain, không cần Spring hoặc CSDL.
 * Tính duy nhất của mã sẽ được kiểm ở phần persistence.
 */
class BranchTest {

    private static final BranchLocation VALID_LOCATION =
            new BranchLocation(10.762622, 106.660172);

    /**
     * Kiểm mã hợp lệ và đối tượng vị trí được giữ nguyên.
     *
     * @param code mã chi nhánh đúng định dạng
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "CN-ABC123",
            "CN-000000",
            "CN-ZZZZZZ"
    })
    void preservesValidCodeAndLocation(String code) {
        Branch branch = new Branch(code, VALID_LOCATION);

        assertEquals(code, branch.code());
        assertSame(VALID_LOCATION, branch.location());
    }

    /**
     * Kiểm mã bị thiếu được báo bằng lỗi đầu vào có chủ đích,
     * không phát sinh NullPointerException.
     */
    @Test
    void rejectsMissingCode() {
        assertInvalidInput(
                () -> new Branch(null, VALID_LOCATION),
                "code is required."
        );
    }

    /**
     * Kiểm mã rỗng hoặc chỉ có khoảng trắng bị từ chối
     * trước khi kiểm định dạng mã.
     *
     * @param code chuỗi không có nội dung
     */
    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = {" ", "\t\n", "\u2003"})
    void rejectsBlankCodes(String code) {
        assertInvalidInput(
                () -> new Branch(code, VALID_LOCATION),
                "code must not be blank."
        );
    }

    /**
     * Kiểm mã sai tiền tố, độ dài hoặc bảng ký tự bị từ chối.
     *
     * <p>Khoảng trắng và xuống dòng không được tự loại bỏ.
     * Chữ thường không được tự chuyển thành chữ hoa.
     *
     * @param code mã không thỏa định dạng chi nhánh
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "XE-ABC123",
            "cn-ABC123",
            "CN-abc123",
            "CN-ABC12",
            "CN-ABC1234",
            "CNABC123",
            " CN-ABC123",
            "CN-ABC123 ",
            "CN-ABC12!",
            "CN-ABC12\u00C9",
            "CN-ABC123\n"
    })
    void rejectsMalformedCodes(String code) {
        assertInvalidInput(
                () -> new Branch(code, VALID_LOCATION),
                "code must contain CN- followed by six uppercase ASCII letters or digits."
        );
    }

    /**
     * Kiểm chi nhánh không thể được tạo khi thiếu vị trí.
     *
     * <p>Mã được giữ hợp lệ để lỗi chỉ phát sinh từ vị trí bị thiếu.
     */
    @Test
    void rejectsMissingLocation() {
        assertInvalidInput(
                () -> new Branch("CN-ABC123", null),
                "location is required."
        );
    }

    /**
     * Kiểm thao tác thất bại với đúng loại exception,
     * mã lỗi, nhóm lỗi và thông báo công khai.
     *
     * @param action thao tác tạo chi nhánh không hợp lệ
     * @param expectedMessage thông báo lỗi mong đợi
     */
    private static void assertInvalidInput(
            Executable action,
            String expectedMessage
    ) {
        DomainException exception = assertThrows(
                DomainException.class,
                action
        );

        assertEquals(ErrorCode.INVALID_REQUEST, exception.errorCode());
        assertEquals(
                DomainException.Category.INVALID_INPUT,
                exception.category()
        );
        assertEquals(expectedMessage, exception.getMessage());
    }
}