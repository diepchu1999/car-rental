package com.carrental.availability.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Bật hạ tầng chạy job dọn giữ chỗ theo BR-103, tách khỏi application theo R11.
 *
 * <p>Luôn bật, không có công tắc vô hiệu hóa yêu cầu tự nhả giữ chỗ.
 * Test đặt nhịp PT24H, đồng thời là độ trễ ban đầu, để job không sửa fixture
 * trong thời gian chạy suite; kiểm lịch chạy thật bằng context riêng.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class AvailabilitySchedulingConfiguration {

    /** Khởi tạo cấu hình scheduler do Spring quản lý. */
    AvailabilitySchedulingConfiguration() {
    }
}
