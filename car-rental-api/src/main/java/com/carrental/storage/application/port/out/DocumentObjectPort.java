package com.carrental.storage.application.port.out;

import java.time.Duration;

/** Cổng S3 tối thiểu; không xoá object và không cấp quyền quản trị bucket. */
public interface DocumentObjectPort {
    /** Ký PUT tới object tạm, URL không được log. */
    String uploadUrl(String key, Duration lifetime);
    /** Đọc không quá giới hạn, kể cả khi object thay đổi sau HEAD; không tin kích thước client khai. */
    byte[] read(String key, long maxBytes);
    /** Ghi đúng bytes đã kiểm vào key mới, không copy lại object tạm có thể đã bị thay. */
    void put(String key, byte[] bytes, String contentType);
    /** Ký GET với response no-store và MIME lấy từ object do backend lưu. */
    String viewUrl(String key, Duration lifetime);
}
