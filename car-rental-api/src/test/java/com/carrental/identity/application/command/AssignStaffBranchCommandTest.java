package com.carrental.identity.application.command;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Kiểm đầu vào gán chi nhánh chỉ có mã nghiệp vụ, không có vai do client quyết định (BR-809). */
class AssignStaffBranchCommandTest {
    /** Factory và constructor giữ đúng hai mã, không đưa vai hoặc ID số vào command. */
    @Test
    void acceptsBusinessCodesWithoutClientSuppliedRoles() {
        var command = AssignStaffBranchCommand.from("TK-ABC123", "CN-DEF456");
        assertEquals(new AssignStaffBranchCommand("TK-ABC123", "CN-DEF456"), command);
        assertEquals(List.of("accountCode", "branchCode"), Arrays.stream(AssignStaffBranchCommand.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList());
    }

    /** Mã tài khoản thiếu hoặc sai không tự được cắt khoảng trắng/đổi hoa. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "TK-abc123", "TK-ABC12", "TK-ABC1234", "CN-ABC123", " TK-ABC123", "123"})
    void rejectsInvalidAccountCode(String code) {
        assertInvalid(() -> AssignStaffBranchCommand.from(code, "CN-DEF456"));
        assertInvalid(() -> new AssignStaffBranchCommand(code, "CN-DEF456"));
    }

    /** Mã chi nhánh phải là CN-<6>, không nhận ID số thay mã. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "CN-def456", "CN-DEF45", "CN-DEF4567", "TK-DEF456", " CN-DEF456", "42"})
    void rejectsInvalidBranchCode(String code) {
        assertInvalid(() -> AssignStaffBranchCommand.from("TK-ABC123", code));
        assertInvalid(() -> new AssignStaffBranchCommand("TK-ABC123", code));
    }

    /** Phải đúng lỗi đầu vào, không xanh nhờ NullPointerException hoặc lỗi nội bộ. */
    private static void assertInvalid(org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrowsExactly(DomainException.class, action);
        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
        assertEquals(DomainException.Category.INVALID_INPUT, failure.category());
    }
}
