package com.carrental.availability.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Bật hạ tầng chạy job dọn giữ chỗ theo BR-103, tách khỏi application theo R11.
 *
 * <p>Mặc định bật. Test tắt hold-sweep-enabled để fixture thời gian cũ không bị
 * job nền sửa ngoài ý muốn; kiểm wiring scheduler bằng context riêng.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "car-rental.availability", name = "hold-sweep-enabled",
        havingValue = "true", matchIfMissing = true)
class AvailabilitySchedulingConfiguration {

    /** Khởi tạo cấu hình scheduler do Spring quản lý. */
    AvailabilitySchedulingConfiguration() {
    }
}
