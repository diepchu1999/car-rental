package com.carrental.identity.application.query;

import com.carrental.shared.error.DomainException;

/** BR-130: chỉ nhận mã GT; chủ tài khoản luôn lấy từ caller, không nhận qua query. */
public record GetDocumentStatusQuery(String code) {
    /** Kiểm định dạng mã nghiệp vụ, không sửa khoảng trắng hoặc đổi hoa đầu vào. */
    public GetDocumentStatusQuery {
        if (code == null || !code.matches("GT-[A-Z0-9]{6}")) {
            throw DomainException.invalidInput("Document code must be a valid business code.");
        }
    }

    /** Factory nhận mã thô từ biên HTTP, không phụ thuộc DTO hay framework. */
    public static GetDocumentStatusQuery from(String code) { return new GetDocumentStatusQuery(code); }

    /** Không tự đưa mã hồ sơ vào log khi in query. */
    @Override public String toString() { return "GetDocumentStatusQuery[redacted]"; }
}
