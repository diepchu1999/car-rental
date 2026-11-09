package com.carrental.storage.api;

/** Tham chiếu ảnh đã kiểm; không chứa bytes ảnh, không trả nguyên record ra REST. */
public record StoredImageRef(String objectKey, String contentType, long sizeBytes, int width, int height) {
    /** Object key không được lọt ra log qua toString mặc định của record. */
    @Override public String toString() { return "StoredImageRef[redacted]"; }
}
