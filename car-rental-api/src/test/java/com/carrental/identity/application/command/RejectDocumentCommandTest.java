package com.carrental.identity.application.command;

import com.carrental.identity.domain.DocumentRejectionReason;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** BR-130: bắt buộc một trong sáu lý do, ghi chú tùy chọn và không phản chiếu dữ liệu nhạy cảm. */
class RejectDocumentCommandTest {
    /** Khóa danh sách theo BR, không chỉ lặp enum rồi vô tình chấp nhận mã mới chưa chốt. */
    @Test void containsExactlySixApprovedReasons() {
        assertEquals(Set.of("ILLEGIBLE", "MISSING_SIDE", "DATA_MISMATCH", "EXPIRED",
                        "HOLDER_MISMATCH", "SUSPECTED_FORGERY"),
                Arrays.stream(DocumentRejectionReason.values()).map(Enum::name).collect(Collectors.toSet()));
    }

    /** Mọi lý do hợp lệ được giữ đúng và đều có thể không kèm ghi chú. */
    @ParameterizedTest @EnumSource(DocumentRejectionReason.class)
    void acceptsEachReasonWithoutNote(DocumentRejectionReason reason) {
        var command = RejectDocumentCommand.from("GT-A1B2C3", reason.name(), null);
        assertEquals("GT-A1B2C3", command.code());
        assertEquals(reason, command.rejectionCode());
        assertNull(command.rejectionNote());
    }

    /** Ghi chú tự do giữ Unicode, xuống dòng và khoảng trắng; không cắt độ dài chưa được BR quy định. */
    @ParameterizedTest @ValueSource(strings = {"  Local test note  ", "Local\r\n\tnote", "Local \uD83D\uDD0D note", "Ghi chú thử"})
    void preservesOptionalNote(String note) {
        assertEquals(note, RejectDocumentCommand.from("GT-A1B2C3", "ILLEGIBLE", note).rejectionNote());
        assertEquals(note, new RejectDocumentCommand("GT-A1B2C3", DocumentRejectionReason.ILLEGIBLE, note).rejectionNote());
    }

    /** Không tự đặt giới hạn độ dài hoặc âm thầm cắt mất nội dung người xác minh nhập. */
    @Test void doesNotTruncateNote() {
        String note = "Local test note. ".repeat(1000);
        assertEquals(note, RejectDocumentCommand.from("GT-A1B2C3", "ILLEGIBLE", note).rejectionNote());
    }

    /** Enum sai không được trim/đổi hoa hay thay bằng lý do mặc định; exception không giữ đầu vào. */
    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings = {" ", "OTHER", "illegible", " ILLEGIBLE", "ILLEGIBLE ", "private-reason\n"})
    void rejectsInvalidReason(String reason) {
        assertInvalid(() -> RejectDocumentCommand.from("GT-A1B2C3", reason, null));
    }

    /** Constructor trực tiếp không được bỏ qua yêu cầu lý do bắt buộc. */
    @Test void constructorRejectsMissingReason() {
        assertInvalid(() -> new RejectDocumentCommand("GT-A1B2C3", null, "private-note"));
    }

    /** Mã GT hợp lệ mới được tham chiếu, cả factory lẫn constructor đều chặn mã sai. */
    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings = {" ", "123", "AN-A1B2C3", "gt-A1B2C3", "GT-a1b2c3",
            "GT-12345", "GT-1234567", " GT-A1B2C3", "GT-A1B2C3\n", "private-code"})
    void rejectsInvalidCode(String code) {
        assertInvalid(() -> RejectDocumentCommand.from(code, "ILLEGIBLE", null));
        assertInvalid(() -> new RejectDocumentCommand(code, DocumentRejectionReason.ILLEGIBLE, null));
    }

    /** Ghi chú có mặt không được trắng, chứa NUL hoặc Unicode hỏng khiến CSDL không lưu nguyên vẹn. */
    @ParameterizedTest @ValueSource(strings = {"", " ", "\t\r\n", "\u2003", "private-note\0", "private-note\uD800", "private-note\uDC00"})
    void rejectsInvalidNote(String note) {
        assertInvalid(() -> RejectDocumentCommand.from("GT-A1B2C3", "ILLEGIBLE", note));
        assertInvalid(() -> new RejectDocumentCommand("GT-A1B2C3", DocumentRejectionReason.ILLEGIBLE, note));
    }

    /** Record không in ghi chú/mã hồ sơ ngay cả khi ghi chú chứa ký tự xuống dòng. */
    @Test void redactsToString() {
        var command = RejectDocumentCommand.from("GT-A1B2C3", "DATA_MISMATCH", "private-note\nLocal test");
        assertEquals("RejectDocumentCommand[redacted]", command.toString());
    }

    /** Sai đầu vào luôn INVALID_REQUEST, không cause/parser hay giá trị nhạy cảm trong thông điệp. */
    private static void assertInvalid(Executable action) {
        var error = assertThrows(DomainException.class, action);
        assertEquals(ErrorCode.INVALID_REQUEST, error.errorCode());
        assertEquals(DomainException.Category.INVALID_INPUT, error.category());
        assertNull(error.getCause());
        assertFalse(error.toString().contains("private-"));
        assertFalse(error.toString().contains("GT-A1B2C3"));
    }
}
