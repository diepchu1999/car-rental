package com.carrental.identity.domain;

/** BR-130: ảnh đã được storage kiểm và chốt; chỉ giữ tham chiếu, không giữ bytes hay URL ký. */
public record DocumentImage(String uploadCode, DocumentSide side, String sealedObjectKey,
                            String contentType, long sizeBytes, int width, int height) {
    /** Ép cấu trúc nội bộ; hạn byte/pixel lấy từ cấu hình storage, không lặp con số nghiệp vụ ở đây. */
    public DocumentImage {
        if (uploadCode == null || !uploadCode.matches("AN-[A-Z0-9]{6}") || side == null
                || sealedObjectKey == null || !sealedObjectKey.matches(
                "sealed/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || !("image/jpeg".equals(contentType) || "image/png".equals(contentType) || "image/heic".equals(contentType))
                || sizeBytes <= 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid sealed document image.");
        }
    }

    /** Không lộ tham chiếu ảnh qua log của record. */
    @Override public String toString() { return "DocumentImage[redacted]"; }
}
