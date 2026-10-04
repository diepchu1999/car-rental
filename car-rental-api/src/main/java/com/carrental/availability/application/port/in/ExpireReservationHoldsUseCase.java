package com.carrental.availability.application.port.in;

/** Cổng nội bộ cho job nhả giữ chỗ quá hạn theo BR-103, không công bố qua REST. */
public interface ExpireReservationHoldsUseCase {

    /**
     * Nhả các HELD có hạn không muộn hơn mốc lấy từ applicationClock.
     *
     * <p>Mỗi lượt dùng một mốc duy nhất. Không tính lại TTL từ cấu hình.
     * Có thể gọi lại; bản ghi đã nhả hoặc đã xác nhận không bị sửa lần nữa.
     * Transaction tham gia transaction bên gọi nếu có.
     *
     * @return số bản ghi được chuyển HELD sang RELEASED trong transaction hiện tại
     */
    int expireHolds();
}
