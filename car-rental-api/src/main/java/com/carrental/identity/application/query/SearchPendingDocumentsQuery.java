package com.carrental.identity.application.query;

import com.carrental.shared.error.DomainException;

/** BR-130, api-guideline §8: phân trang hàng chờ admin, không nhận chủ, chi nhánh hoặc dữ liệu giấy tờ. */
public record SearchPendingDocumentsQuery(Integer limit, Long offset) {
    /** Mặc định 20/0; vượt 100 hoặc offset âm là lỗi đầu vào, không tự cắt hoặc sửa giá trị. */
    public SearchPendingDocumentsQuery {
        limit = limit == null ? 20 : limit;
        offset = offset == null ? 0L : offset;
        if (limit < 1 || limit > 100) throw DomainException.invalidInput("limit must be between 1 and 100.");
        if (offset < 0) throw DomainException.invalidInput("offset must not be negative.");
    }

    /** Tạo từ tham số thô, không phụ thuộc DTO hoặc framework HTTP. */
    public static SearchPendingDocumentsQuery from(Integer limit, Long offset) {
        return new SearchPendingDocumentsQuery(limit, offset);
    }
}
