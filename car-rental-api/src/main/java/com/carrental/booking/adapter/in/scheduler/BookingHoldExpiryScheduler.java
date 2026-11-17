package com.carrental.booking.adapter.in.scheduler;

import com.carrental.booking.application.port.in.SweepExpiredBookingHoldsUseCase;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** BR-103: job duy nhất điều phối hết hạn, bao gồm đơn và giữ chỗ; giữ nhịp cấu hình hiện có. */
@Component
class BookingHoldExpiryScheduler {
    private final SweepExpiredBookingHoldsUseCase useCase;

    /** Adapter chỉ gọi use case; lỗi quét danh sách do hạ tầng scheduler ghi log và chạy lại lượt sau. */
    BookingHoldExpiryScheduler(SweepExpiredBookingHoldsUseCase useCase) {
        this.useCase = useCase;
    }

    /** Chờ một nhịp sau khởi động, sau mỗi lượt kết thúc mới đợi nhịp tiếp theo. */
    @Scheduled(fixedDelayString = "#{@reservationHoldSweepInterval.toString()}",
            initialDelayString = "#{@reservationHoldSweepInterval.toString()}")
    public void sweep() {
        useCase.sweep();
    }
}
