package com.carrental.identity.application.command;

import com.carrental.shared.error.DomainException;

/** BR-130: mở đúng mã GT; requestId do biên HTTP lấy từ ngữ cảnh đã chuẩn hoá, không lấy từ body. */
public record OpenDocumentViewSessionCommand(String code, String requestId) {
    /** Chặn mã hồ sơ sai và requestId nội bộ không an toàn, không phản chiếu giá trị vào thông báo. */
    public OpenDocumentViewSessionCommand {
        if (code == null || !code.matches("GT-[A-Z0-9]{6}")) {
            throw DomainException.invalidInput("A valid document code is required.");
        }
        if (requestId == null || !requestId.matches("[A-Za-z0-9-]{1,64}")) {
            throw new IllegalArgumentException("A safe requestId is required.");
        }
    }

    /** Nhận giá trị thô, không nhận chủ tài khoản, người xem hoặc object key do client tự chọn. */
    public static OpenDocumentViewSessionCommand from(String code, String requestId) {
        return new OpenDocumentViewSessionCommand(code, requestId);
    }
}
