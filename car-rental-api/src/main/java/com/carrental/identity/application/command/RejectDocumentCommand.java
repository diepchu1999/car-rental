package com.carrental.identity.application.command;

import com.carrental.identity.domain.DocumentRejectionReason;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.validation.Validations;

import java.nio.charset.StandardCharsets;

/** BR-130: từ chối bản nộp bằng lý do cố định và ghi chú tùy chọn; không nhận người/thời điểm duyệt. */
public record RejectDocumentCommand(String code, DocumentRejectionReason rejectionCode,
        String rejectionNote) {
    /** Bảo vệ cả constructor trực tiếp; null biểu diễn không ghi chú, không tự sửa nội dung. */
    public RejectDocumentCommand {
        if (code == null || !code.matches("GT-[A-Z0-9]{6}")) {
            throw DomainException.invalidInput("A valid document code is required.");
        }
        Validations.required(rejectionCode, "rejectionCode");
        if (rejectionNote != null) {
            Validations.requiredText(rejectionNote, "rejectionNote");
            // PostgreSQL text không chứa NUL; Unicode hỏng không được âm thầm đổi khi mã hoá UTF-8.
            // Ghi chú tự do vẫn giữ xuống dòng/tab; an toàn log bằng che dữ liệu, không sửa dữ liệu.
            if (rejectionNote.indexOf('\0') >= 0
                    || !StandardCharsets.UTF_8.newEncoder().canEncode(rejectionNote)) {
                throw DomainException.invalidInput("rejectionNote must contain valid text without NUL.");
            }
        }
    }

    /** Parse mã lý do chính xác từ giá trị thô, không nhận DTO hoặc giá trị thay thế mặc định. */
    public static RejectDocumentCommand from(String code, String rejectionCode, String rejectionNote) {
        return new RejectDocumentCommand(code, parseReason(rejectionCode), rejectionNote);
    }

    /** Bỏ cause của valueOf vì thông báo của nó chứa nguyên đầu vào, có thể là thông tin giấy tờ. */
    private static DocumentRejectionReason parseReason(String value) {
        Validations.required(value, "rejectionCode");
        try {
            return DocumentRejectionReason.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            throw DomainException.invalidInput("Unsupported rejectionCode.");
        }
    }

    /** Ghi chú có thể chứa dữ liệu giấy tờ; không để record tự in các thành phần ra log. */
    @Override
    public String toString() { return "RejectDocumentCommand[redacted]"; }
}
