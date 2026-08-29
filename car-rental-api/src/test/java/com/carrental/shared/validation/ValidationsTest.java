package com.carrental.shared.validation;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

/**
 * Kiểm tra hợp đồng của các tiện ích kiểm dữ liệu đầu vào.
 *
 * <p>Test chạy trực tiếp với Java và JUnit, không cần Spring
 * hoặc kết nối CSDL.
 *
 * <p>Các kiểm tra phân biệt lỗi dữ liệu đầu vào với lỗi lập trình
 * và bảo đảm giá trị hợp lệ được giữ nguyên.
 */
class ValidationsTest {

    /**
     * Cung cấp các giá trị khác null thuộc nhiều kiểu dữ liệu.
     *
     * <p>Số 0, false và chuỗi trắng vẫn hợp lệ đối với phép kiểm
     * chỉ yêu cầu giá trị khác null.
     *
     * @return các giá trị cần được required chấp nhận
     */
    private static Stream<Object> presentValues() {
        return Stream.of(
                new Object(),
                0,
                false,
                "",
                " \t\n"
        );
    }

    /**
     * Kiểm required trả lại chính đối tượng đã nhận khi khác null,
     * không tự áp thêm điều kiện hoặc chuyển đổi dữ liệu.
     *
     * @param value giá trị khác null cần được giữ nguyên
     */
    @ParameterizedTest
    @MethodSource("presentValues")
    void requiredPreservesNonNullValues(Object value) {
        Object result = Validations.required(value, "value");

        assertSame(value, result);
    }

    /**
     * Kiểm required từ chối null bằng đúng mã lỗi, nhóm lỗi
     * và thông báo dành cho dữ liệu đầu vào bị thiếu.
     */
    @Test
    void requiredRejectsNull() {
        DomainException exception = assertThrows(
                DomainException.class,
                () -> Validations.required(null, "value")
        );

        assertInvalidInput(exception, "value is required.");
    }

    /**
     * Kiểm requiredText từ chối null bằng lỗi thiếu dữ liệu,
     * không phát sinh NullPointerException.
     */
    @Test
    void requiredTextRejectsNull() {
        DomainException exception = assertThrows(
                DomainException.class,
                () -> Validations.requiredText(null, "name")
        );

        assertInvalidInput(exception, "name is required.");
    }

    /**
     * Kiểm chuỗi rỗng và các dạng khoảng trắng bị từ chối.
     *
     * <p>Bao gồm khoảng trắng Unicode để kiểm đúng hành vi
     * của String.isBlank(), không chỉ dấu cách thông thường.
     *
     * @param value chuỗi không có nội dung
     */
    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = {" ", "\t\n", "\u2003"})
    void requiredTextRejectsBlankValues(String value) {
        DomainException exception = assertThrows(
                DomainException.class,
                () -> Validations.requiredText(value, "name")
        );

        assertInvalidInput(exception, "name must not be blank.");
    }

    /**
     * Kiểm chuỗi có nội dung được giữ nguyên tham chiếu,
     * khoảng trắng đầu cuối và cách viết hoa thường.
     *
     * @param value chuỗi có nội dung cần được giữ nguyên
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "Sample",
            "  MiXeD  ",
            "\tSample\n"
    })
    void requiredTextPreservesOriginalValue(String value) {
        String result = Validations.requiredText(value, "name");

        assertSame(value, result);
    }

    /**
     * Kiểm tên trường sai luôn được coi là lỗi lập trình.
     *
     * <p>Mỗi lượt kiểm cả hai phương thức với giá trị có mặt
     * và giá trị null. Việc kiểm tên trường phải diễn ra trước
     * kiểm dữ liệu, tránh báo nhầm lỗi lập trình thành lỗi đầu vào.
     *
     * @param fieldName tên trường null, rỗng hoặc chỉ có khoảng trắng
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsInvalidFieldNamesBeforeCheckingValues(String fieldName) {
        assertInvalidFieldName(
                () -> Validations.required("Sample", fieldName)
        );

        assertInvalidFieldName(
                () -> Validations.required(null, fieldName)
        );

        assertInvalidFieldName(
                () -> Validations.requiredText("Sample", fieldName)
        );

        assertInvalidFieldName(
                () -> Validations.requiredText(null, fieldName)
        );
    }

    /**
     * Kiểm đầy đủ hợp đồng của một lỗi dữ liệu đầu vào.
     *
     * @param exception lỗi đã bắt được từ phương thức đang kiểm thử
     * @param expectedMessage thông báo công khai mong đợi
     */
    private static void assertInvalidInput(
            DomainException exception,
            String expectedMessage
    ) {
        assertEquals(ErrorCode.INVALID_REQUEST, exception.errorCode());
        assertEquals(
                DomainException.Category.INVALID_INPUT,
                exception.category()
        );
        assertEquals(expectedMessage, exception.getMessage());
    }

    /**
     * Kiểm lời gọi với tên trường sai ném đúng lỗi lập trình
     * cùng thông báo chỉ rõ tham số bị dùng sai.
     *
     * @param action lời gọi tiện ích có tên trường không hợp lệ
     */
    private static void assertInvalidFieldName(Executable action) {
        IllegalArgumentException exception = assertThrowsExactly(
                IllegalArgumentException.class,
                action
        );

        assertEquals(
                "fieldName must not be blank.",
                exception.getMessage()
        );
    }
}