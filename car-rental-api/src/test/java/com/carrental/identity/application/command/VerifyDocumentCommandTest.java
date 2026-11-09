package com.carrental.identity.application.command;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** BR-130/database-guideline §2: xác minh tham chiếu bản nộp qua GT, không nhận khóa chính số. */
class VerifyDocumentCommandTest {
    /** Factory và constructor đều giữ nguyên mã hợp lệ, chưa khẳng định hồ sơ tồn tại/được duyệt. */
    @Test void acceptsBusinessCode() {
        assertEquals("GT-A1B2C3", VerifyDocumentCommand.from("GT-A1B2C3").code());
        assertEquals("GT-Z9Y8X7", new VerifyDocumentCommand("GT-Z9Y8X7").code());
    }

    /** Chặn thiếu mã, sai namespace/độ dài/chữ thường/khoảng trắng và dữ liệu điều khiển ở cả hai lối tạo. */
    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings = {" ", "123", "AN-A1B2C3", "gt-A1B2C3", "GT-a1b2c3",
            "GT-12345", "GT-1234567", " GT-A1B2C3", "GT-A1B2C3\n", "private-document"})
    void rejectsInvalidCodesWithoutReflectingInput(String code) {
        assertInvalid(() -> VerifyDocumentCommand.from(code));
        assertInvalid(() -> new VerifyDocumentCommand(code));
    }

    /** Command không rò mã hồ sơ qua toString tự sinh của record. */
    @Test void redactsToString() {
        assertEquals("VerifyDocumentCommand[redacted]", VerifyDocumentCommand.from("GT-A1B2C3").toString());
    }

    /** Kiểm đúng lỗi đầu vào và thông điệp cố định, không giữ cause từ parser. */
    private static void assertInvalid(org.junit.jupiter.api.function.Executable action) {
        var error = assertThrows(DomainException.class, action);
        assertEquals(ErrorCode.INVALID_REQUEST, error.errorCode());
        assertEquals(DomainException.Category.INVALID_INPUT, error.category());
        assertEquals("A valid document code is required.", error.getMessage());
        assertNull(error.getCause());
    }
}
