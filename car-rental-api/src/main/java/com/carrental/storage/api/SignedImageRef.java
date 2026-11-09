package com.carrental.storage.api;

import java.time.Instant;

/** URL xem ảnh có thời hạn; chỉ chuyển tới caller đã được kiểm quyền và audit. */
public record SignedImageRef(String url, Instant expiresAt) {
    /** Chữ ký là credential tạm thời, tuyệt đối không in trong log. */
    @Override public String toString() { return "SignedImageRef[redacted]"; }
}
