package com.carrental.availability.adapter.out.configuration;

import com.carrental.availability.application.port.out.ReadReservationPolicyPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Đọc thời hạn giữ chỗ từ cấu hình ứng dụng theo phạm vi local của BR-225.
 *
 * <p>Đây là nguồn tạm thời trước khi có module config: chưa đáp ứng
 * yêu cầu admin chỉnh giá trị và lưu lịch sử hiệu lực theo thời gian.
 * Đổi cấu hình cần khởi động lại ứng dụng.
 *
 * <p>Adapter nhận nhưng chưa sử dụng giá trị mốc at để lựa chọn chính sách.
 * Sau này thay bằng adapter đọc cấu hình có hiệu lực tại at theo ADR-0013,
 * không cần đổi hợp đồng port. Nhịp dọn không đi qua adapter này.
 */
@Component
class ConfiguredReservationPolicyAdapter implements ReadReservationPolicyPort {

    private final Duration duration;

    /**
     * Nhận thời hạn đã được kiểm tra khi tạo bean cấu hình.
     *
     * @param duration thời hạn giữ chỗ lớn hơn không
     */
    ConfiguredReservationPolicyAdapter(
            @Qualifier("reservationHoldDuration") Duration duration
    ) {
        this.duration = Objects.requireNonNull(duration, "duration must not be null.");
    }

    /**
     * Trả giá trị cố định đã đọc khi khởi động, không tự đọc đồng hồ.
     *
     * <p>Kiểm mốc không null để giữ hợp đồng port nhưng không phân nhánh
     * theo mốc trong nguồn cấu hình hiện tại. Không tính thời điểm hết hạn
     * và không tác động đến bản ghi giữ chỗ đã tồn tại.
     *
     * @param at mốc thời gian do application cung cấp, không được null
     * @return thời hạn đã được kiểm tra từ cấu hình ứng dụng
     * @throws NullPointerException nếu không cung cấp mốc thời gian
     */
    @Override
    public Duration holdDuration(Instant at) {
        Objects.requireNonNull(at, "at must not be null.");
        return duration;
    }
}
