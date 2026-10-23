package com.carrental.identity.domain;

/**
 * Danh tính bất biến của bản nộp, dùng làm AAD để không tráo bản mã giữa tài khoản, loại hay phiên bản.
 * Đây không phải bằng chứng phân quyền: use case vẫn phải kiểm người gọi theo security-guideline §2.
 *
 * @param accountCode mã tài khoản sở hữu
 * @param documentType loại giấy tờ theo BR-130
 * @param documentCode mã GT của đúng lần nộp, không phải mã của bản nộp mới nhất
 */
public record DocumentProtectionContext(String accountCode, DocumentType documentType, String documentCode) {
    /** Chỉ nhận các định danh có cấu trúc cố định, không đưa giá trị sai vào exception. */
    public DocumentProtectionContext {
        if (accountCode == null || !accountCode.matches("TK-[A-Z0-9]{6}") || documentType == null
                || documentCode == null || !documentCode.matches("GT-[A-Z0-9]{6}")) {
            throw new IllegalArgumentException("Invalid document protection context.");
        }
    }
}
