package com.carrental.booking.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Nội dung phát lại trong phạm vi khách, không chứa token hoặc body HTTP (api-guideline §5).
 * @param customerAccountCode người gọi đã xác thực
 * @param key key nguyên bản, không trim để gộp hai key khác nhau
 * @param quoteCode toàn bộ nội dung nghiệp vụ của request tạo đơn
 * @param bookingCode mã kết quả được giữ tới commit bằng FK hoãn
 * @param createdAt thời điểm lần chiếm key này
 * @param expiresAt hết hiệu lực đúng 24 giờ sau createdAt, không gia hạn khi phát lại
 */
public record BookingIdempotency(String customerAccountCode, String key, String quoteCode,
        String bookingCode, Instant createdAt, Instant expiresAt) {
    private static final Duration RETENTION = Duration.ofHours(24);

    /** Bảo vệ dữ liệu lưu; không nhận TTL do bên gọi lựa chọn. */
    public BookingIdempotency {
        if (customerAccountCode == null || !customerAccountCode.matches("TK-[A-Z0-9]{6}")
                || key == null || key.isBlank()
                || quoteCode == null || !quoteCode.matches("BG-[0-9]{2}(0[1-9]|1[0-2])-[A-Z0-9]{6}")
                || bookingCode == null || !bookingCode.matches("DT-[0-9]{2}(0[1-9]|1[0-2])-[A-Z0-9]{6}")
                || createdAt == null || expiresAt == null || createdAt.getNano() % 1_000 != 0
                || expiresAt.getNano() % 1_000 != 0 || !createdAt.plus(RETENTION).equals(expiresAt)) {
            throw new IllegalArgumentException("Invalid booking idempotency record.");
        }
    }

    /** Tạo cửa sổ trượt 24 giờ cho lần ghi mới, theo api-guideline §5. */
    public static BookingIdempotency create(String customerAccountCode, String key, String quoteCode,
            String bookingCode, Instant at) {
        Objects.requireNonNull(at, "at");
        return new BookingIdempotency(customerAccountCode, key, quoteCode, bookingCode, at, at.plus(RETENTION));
    }
}
