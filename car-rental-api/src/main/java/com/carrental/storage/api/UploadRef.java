package com.carrental.storage.api;

import java.time.Instant;

/** Thông tin nội bộ của lượt tải; key không phải mã AN và không được log. */
public record UploadRef(String objectKey, String url, Instant expiresAt) {
    /** Che URL ký và object key khi logger vô tình nhận cả record. */
    @Override public String toString() { return "UploadRef[redacted]"; }
}
