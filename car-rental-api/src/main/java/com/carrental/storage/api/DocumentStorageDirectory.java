package com.carrental.storage.api;

/**
 * Kho ảnh riêng tư BR-130, chỉ được gọi sau kiểm quyền ở module sở hữu hồ sơ.
 * Không có REST ký object tuỳ ý; identity phải gắn upload key với đúng chủ và mặt giấy tờ.
 */
public interface DocumentStorageDirectory {
    /** Cấp vùng tải tạm mới, không chứa PII trong tên object. */
    UploadRef createUpload();
    /** Đọc có giới hạn, kiểm nội dung rồi lưu chính bytes đã kiểm dưới tên mới; không ghi đè ảnh đã chốt. */
    StoredImageRef sealUpload(String uploadKey);
    /** Cấp URL ngắn hạn cho ảnh đã chốt; caller chịu trách nhiệm audit trước khi trả HTTP response. */
    SignedImageRef createView(String sealedKey);
}
