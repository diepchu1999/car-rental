package com.carrental.storage.domain;

/** Kết quả kiểm ảnh thực tế, không dùng MIME hay kích thước client khai. */
public record ImageMetadata(String contentType, int width, int height) {
    /** Metadata phải thuộc một trong ba loại ảnh BR-130 và có kích thước dương. */
    public ImageMetadata {
        if (!java.util.Set.of("image/jpeg", "image/png", "image/heic").contains(contentType)
                || width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid image metadata.");
    }
}
