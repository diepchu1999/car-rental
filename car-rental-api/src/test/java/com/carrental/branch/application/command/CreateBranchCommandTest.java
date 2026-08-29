package com.carrental.branch.application.command;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Kiểm việc tạo command và chuyển tọa độ vào đối tượng domain.
 *
 * <p>Các giới hạn địa lý đã được kiểm trong BranchLocationTest.
 * Lớp này kiểm phần kết nối dữ liệu và điều kiện vị trí bắt buộc
 * của command, không cần Spring hoặc CSDL.
 */
class CreateBranchCommandTest {

    /**
     * Kiểm factory giữ đúng thứ tự và giá trị của hai tọa độ.
     */
    @Test
    void createsCommandWithValidatedLocation() {
        CreateBranchCommand command = CreateBranchCommand.from(
                10.762622,
                106.660172
        );

        assertEquals(10.762622, command.location().latitude());
        assertEquals(106.660172, command.location().longitude());
    }

    /**
     * Kiểm constructor trực tiếp không cho phép command thiếu vị trí.
     */
    @Test
    void rejectsMissingLocation() {
        DomainException exception = assertThrows(
                DomainException.class,
                () -> new CreateBranchCommand(null)
        );

        assertEquals(ErrorCode.INVALID_REQUEST, exception.errorCode());
        assertEquals(
                DomainException.Category.INVALID_INPUT,
                exception.category()
        );
        assertEquals("location is required.", exception.getMessage());
    }
}