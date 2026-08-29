package com.carrental.shared.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Lắp ráp đồng hồ dùng chung cho toàn bộ ứng dụng.
 *
 * <p>Cấu hình nằm ngoài application theo ADR-0004,
 * mục làm rõ ngày 21/09/2026.
 *
 * <p>Múi giờ lấy từ cấu hình, không phụ thuộc múi giờ mặc định
 * của JVM và không được từng module tự quyết định riêng.
 */
@Configuration(proxyBeanMethods = false)
class TimeConfiguration {

    /**
     * Tạo đồng hồ hệ thống với múi giờ được cấu hình.
     *
     * <p>Giá trị mặc định được khai báo trong application.yml,
     * không lặp lại trong Java.
     *
     * <p>Nếu cấu hình múi giờ không hợp lệ, việc tạo bean thất bại
     * để ứng dụng dừng ngay khi khởi động, không âm thầm dùng múi giờ khác.
     *
     * @param timeZone định danh múi giờ từ car-rental.time-zone
     * @return đồng hồ dùng chung cho các module
     * @throws java.time.DateTimeException nếu định danh múi giờ không hợp lệ
     */
    @Bean
    Clock applicationClock(
            @Value("${car-rental.time-zone}") String timeZone
    ) {
        return Clock.system(ZoneId.of(timeZone));
    }
}