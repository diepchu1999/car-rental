package com.carrental.branch.application.query;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Kiểm tra dữ liệu đầu vào và việc giữ nguyên mã của query tra cứu chi nhánh.
 */
class GetBranchQueryTest {

    /**
     * Chứng minh constructor và factory đều giữ nguyên mã chi nhánh hợp lệ.
     */
    @Test
    void preservesBranchCode() {
        String code = "CN-ABC123";

        assertEquals(code, new GetBranchQuery(code).code());
        assertEquals(code, GetBranchQuery.from(code).code());
    }

    /**
     * Chứng minh query không áp đặt lại định dạng mã dùng khi tạo chi nhánh.
     *
     * <p>Việc xác định mã này có tồn tại hay không thuộc application service.
     */
    @Test
    void preservesNonBlankLookupValueWithoutNormalization() {
        String code = " unknown-branch ";

        assertEquals(code, new GetBranchQuery(code).code());
        assertEquals(code, GetBranchQuery.from(code).code());
    }

    /**
     * Kiểm tra cả constructor và factory đều từ chối mã thiếu hoặc trắng.
     *
     * @param code mã đầu vào không hợp lệ
     * @param expectedMessage thông báo lỗi mong đợi
     */
    @ParameterizedTest
    @MethodSource("invalidCodes")
    void rejectsMissingOrBlankCode(
            String code,
            String expectedMessage
    ) {
        assertInvalidInput(
                () -> new GetBranchQuery(code),
                expectedMessage
        );

        assertInvalidInput(
                () -> GetBranchQuery.from(code),
                expectedMessage
        );
    }

    /**
     * Cung cấp các trường hợp mã thiếu, rỗng và chứa khoảng trắng.
     *
     * @return dữ liệu đầu vào cùng thông báo lỗi tương ứng
     */
    private static Stream<Arguments> invalidCodes() {
        return Stream.of(
                Arguments.of(null, "code is required."),
                Arguments.of("", "code must not be blank."),
                Arguments.of(" ", "code must not be blank."),
                Arguments.of("\t\n", "code must not be blank."),
                Arguments.of("\u2003", "code must not be blank.")
        );
    }

    /**
     * Kiểm tra lỗi đầu vào có đúng mã, nhóm lỗi và thông báo.
     *
     * @param action thao tác phải bị từ chối
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

        assertEquals(
                ErrorCode.INVALID_REQUEST,
                exception.errorCode()
        );
        assertEquals(
                DomainException.Category.INVALID_INPUT,
                exception.category()
        );
        assertEquals(expectedMessage, exception.getMessage());
    }
}