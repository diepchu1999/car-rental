package com.carrental.availability.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.format.DateTimeParseException;

/**
 * Đọc và kiểm tra cấu hình thời gian của availability ngay khi khởi động.
 *
 * <p>Thời hạn giữ chỗ phục vụ BR-103, BR-225; nhịp dọn là tham số
 * vận hành riêng. Các giá trị mặc định chỉ nằm trong application.yml.
 * Cấu hình sai làm tạo bean thất bại, không âm thầm thay bằng mặc định.
 */
@Configuration(proxyBeanMethods = false)
class AvailabilityConfiguration {

    /** Khởi tạo lớp lắp ráp cấu hình do Spring quản lý. */
    AvailabilityConfiguration() {
    }

    /**
     * Cung cấp thời hạn giữ chỗ dương cho adapter đọc chính sách.
     *
     * @param value thời lượng ISO-8601 từ cấu hình ứng dụng
     * @return thời hạn giữ chỗ đã kiểm tra
     * @throws IllegalArgumentException nếu thiếu, sai định dạng hoặc không dương
     */
    @Bean
    Duration reservationHoldDuration(
            @Value("${car-rental.availability.hold-duration}") String value
    ) {
        return positiveDuration(value, "car-rental.availability.hold-duration");
    }

    /**
     * Cung cấp nhịp dọn dương để scheduler sử dụng trực tiếp, không qua port.
     *
     * <p>Đây là tham số vận hành, không phải chính sách có lịch sử hiệu lực.
     * Bean được kiểm tra khi khởi động kể cả khi scheduler được tắt trong test.
     *
     * @param value thời lượng ISO-8601 từ cấu hình ứng dụng
     * @return nhịp dọn đã kiểm tra
     * @throws IllegalArgumentException nếu thiếu, sai định dạng hoặc không dương
     */
    @Bean
    Duration reservationHoldSweepInterval(
            @Value("${car-rental.availability.hold-sweep-interval}") String value
    ) {
        return positiveDuration(value, "car-rental.availability.hold-sweep-interval");
    }

    /**
     * Phân tích thời lượng và từ chối cấu hình không dùng được.
     *
     * @param value chuỗi ISO-8601, ví dụ PT1H hoặc PT30S
     * @param propertyName tên thuộc tính để chẩn đoán lỗi
     * @return thời lượng lớn hơn không
     * @throws IllegalArgumentException nếu giá trị thiếu, sai hoặc không dương
     */
    private static Duration positiveDuration(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(propertyName + " must not be blank.");
        }

        Duration duration;
        try {
            duration = Duration.parse(value);
        } catch (DateTimeParseException failure) {
            throw new IllegalArgumentException(
                    propertyName + " must be a valid ISO-8601 duration.", failure
            );
        }

        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(propertyName + " must be greater than zero.");
        }

        return duration;
    }
}
