package com.carrental.audit.api;

/** Cổng chỉ thêm nhật ký theo BR-130; không cung cấp sửa/xoá hay nhận payload tự do. */
public interface AuditDirectory {
    /**
     * Ghi một lần mở view-session và commit độc lập trước khi trả về.
     * Người gọi phải kiểm quyền trước; nếu ghi thất bại, không được trả dữ liệu/URL ảnh.
     * Không coi requestId là idempotency key: mỗi lần mở phải là một event.
     */
    void recordDocumentView(String actorAccountCode, String subjectAccountCode,
                            String documentCode, String requestId);
}
