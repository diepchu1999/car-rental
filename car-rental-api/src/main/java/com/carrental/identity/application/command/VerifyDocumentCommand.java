package com.carrental.identity.application.command;

import com.carrental.shared.error.DomainException;

/** BR-130: yêu cầu xác minh một bản nộp GT; người duyệt và thời điểm do use case xác định. */
public record VerifyDocumentCommand(String code) {
    /** Chỉ kiểm định dạng; quyền duyệt, tồn tại và trạng thái phải kiểm trong use case/transaction. */
    public VerifyDocumentCommand {
        if (code == null || !code.matches("GT-[A-Z0-9]{6}")) {
            throw DomainException.invalidInput("A valid document code is required.");
        }
    }

    /** Nhận mã nghiệp vụ thô, không phụ thuộc DTO hay nhận ID số từ bên ngoài. */
    public static VerifyDocumentCommand from(String code) {
        return new VerifyDocumentCommand(code);
    }

    /** Không đưa định danh hồ sơ vào log khi command bị nối vào thông báo. */
    @Override
    public String toString() { return "VerifyDocumentCommand[redacted]"; }
}
