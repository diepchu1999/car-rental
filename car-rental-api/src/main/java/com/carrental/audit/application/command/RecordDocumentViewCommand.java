package com.carrental.audit.application.command;

import java.util.Objects;

/** Đầu vào nội bộ của BR-130, chỉ nhận định danh đã kiểm quyền, không nhận dữ liệu giấy tờ. */
public record RecordDocumentViewCommand(String actorAccountCode, String subjectAccountCode,
                                        String documentCode, String requestId) {
    /** Kiểm hình dạng trước khi ghi, không lặp lại giá trị không hợp lệ trong exception. */
    public RecordDocumentViewCommand {
        require(actorAccountCode, "TK-[A-Z0-9]{6}", "actorAccountCode");
        require(subjectAccountCode, "TK-[A-Z0-9]{6}", "subjectAccountCode");
        require(documentCode, "GT-[A-Z0-9]{6}", "documentCode");
        require(requestId, "[A-Za-z0-9-]{1,64}", "requestId");
    }

    /** Chỉ nêu tên trường để dữ liệu đầu vào không đi qua đường lỗi ra log. */
    private static void require(String value, String pattern, String field) {
        Objects.requireNonNull(value, field + " is required.");
        if (!value.matches(pattern)) throw new IllegalArgumentException(field + " is invalid.");
    }
}
