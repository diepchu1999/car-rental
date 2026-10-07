package com.carrental.search.config;

import com.carrental.search.domain.SearchRadiusSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Lắp ráp bán kính BR-125; không đặt @Configuration trong application (ADR-0004/R11). */
@Configuration(proxyBeanMethods = false)
class SearchConfiguration {
    /** Đọc cả hai giá trị từ YAML; constructor kiểm sai cấu hình để ứng dụng dừng ngay lúc khởi động. */
    @Bean
    SearchRadiusSettings searchRadiusSettings(
            @Value("${car-rental.search.default-radius-km}") double defaultRadiusKm,
            @Value("${car-rental.search.max-radius-km}") double maxRadiusKm) {
        return new SearchRadiusSettings(defaultRadiusKm, maxRadiusKm);
    }
}
