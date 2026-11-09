package com.carrental.storage.domain;

import java.time.Duration;
import java.util.Objects;

/** Giới hạn BR-130, truyền từ cấu hình, không đóng cứng vào bộ đọc ảnh. */
public record DocumentImagePolicy(long maxBytes, long maxPixels, Duration uploadLifetime, Duration viewLifetime) {
    /** Kiểm hữu hạn và giới hạn kỹ thuật để đọc tối đa maxBytes + 1 an toàn trong một mảng. */
    public DocumentImagePolicy {
        if (maxBytes <= 0 || maxBytes >= Integer.MAX_VALUE || maxPixels <= 0) {
            throw new IllegalArgumentException("Document image limits must be positive and bounded.");
        }
        checkDuration(uploadLifetime);
        checkDuration(viewLifetime);
    }

    /** S3 nhận số giây nguyên, tối đa 7 ngày; không âm thầm làm tròn cấu hình. */
    private static void checkDuration(Duration value) {
        Objects.requireNonNull(value, "URL lifetime is required.");
        if (value.isNegative() || value.isZero() || value.getNano() != 0 || value.getSeconds() > 604800) {
            throw new IllegalArgumentException("URL lifetime must be whole seconds between 1 and 604800.");
        }
    }
}
