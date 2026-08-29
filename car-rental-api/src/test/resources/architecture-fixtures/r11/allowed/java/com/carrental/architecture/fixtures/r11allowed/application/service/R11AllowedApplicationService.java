package com.carrental.architecture.fixtures.r11allowed.application.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Chứng minh application được dùng Spring cho tiêm phụ thuộc
 * và đánh dấu transaction theo ADR-0004.
 *
 * <p>Dòng bên dưới chỉ là nội dung comment, không phải import:
 * import org.springframework.context.annotation.Configuration;
 */
@Service
class R11AllowedApplicationService {

    private final Clock clock;

    /**
     * Nhận đồng hồ bằng constructor và qualifier được R11 cho phép.
     *
     * @param clock đồng hồ được cung cấp từ bên ngoài
     */
    R11AllowedApplicationService(
            @Qualifier("applicationClock") Clock clock
    ) {
        this.clock = clock;
    }

    /**
     * Minh họa phương thức application được đánh dấu transaction.
     *
     * @return thời điểm lấy từ đồng hồ được tiêm
     */
    @Transactional(readOnly = true)
    public Instant currentInstant() {
        return clock.instant();
    }
}

/**
 * Chứng minh Component cũng được phép, không chỉ riêng Service.
 *
 * <p>Lớp nằm trong tài nguyên fixture, không được Spring đăng ký.
 */
@Component
class R11AllowedApplicationComponent {

    /**
     * Cung cấp chuỗi chứa chữ giống import để kiểm scanner không bắt nhầm.
     *
     * @return chuỗi minh họa, không phải dependency Java
     */
    String exampleText() {
        return "import java.sql.Connection;";
    }
}