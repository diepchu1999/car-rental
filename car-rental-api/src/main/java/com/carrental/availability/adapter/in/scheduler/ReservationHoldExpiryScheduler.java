package com.carrental.availability.adapter.in.scheduler;

import com.carrental.availability.application.port.in.ExpireReservationHoldsUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Gọi use case nhả giữ chỗ theo BR-103, không chứa SQL hoặc quy tắc chuyển trạng thái.
 *
 * <p>Nhịp lấy từ bean cấu hình đã kiểm tra dương, không đi qua port chính sách.
 * Chờ một nhịp sau khởi động; sau mỗi lượt kết thúc mới đợi nhịp tiếp theo.
 * Hai instance có thể cùng chạy: UPDATE có điều kiện bảo vệ trạng thái ở CSDL.
 * Lỗi thoát ra được hạ tầng scheduler ghi nhận; lượt định kỳ sau vẫn được thực hiện.
 */
@Component
@ConditionalOnProperty(prefix = "car-rental.availability", name = "hold-sweep-enabled",
        havingValue = "true", matchIfMissing = true)
class ReservationHoldExpiryScheduler {

    private final ExpireReservationHoldsUseCase useCase;

    /** Nhận cổng nghiệp vụ có transaction, không gọi trực tiếp adapter ghi. */
    ReservationHoldExpiryScheduler(ExpireReservationHoldsUseCase useCase) {
        this.useCase = useCase;
    }

    /** Kích hoạt một lượt dọn; không nuốt lỗi hoặc tự thử lại trong cùng lượt. */
    @Scheduled(fixedDelayString = "#{@reservationHoldSweepInterval.toString()}",
            initialDelayString = "#{@reservationHoldSweepInterval.toString()}")
    public void sweep() {
        useCase.expireHolds();
    }
}
