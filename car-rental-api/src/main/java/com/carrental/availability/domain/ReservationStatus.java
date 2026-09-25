package com.carrental.availability.domain;

/**
 * Định nghĩa trạng thái của khóa lịch theo status-flow §2.
 *
 * <p>HELD, CONFIRMED, IN_USE, BLOCKED và COMPLETED
 * đều chặn các khoảng thời gian chồng lên khoảng đã lưu.
 * RELEASED không chặn lịch.
 *
 * <p>COMPLETED vẫn chặn để bảo toàn khoảng đệm theo
 * BR-109 và BR-116. Khi khoảng đã nằm trong quá khứ,
 * nó không chồng lên các lượt thuê trong tương lai.
 *
 * <p>Enum chỉ định nghĩa trạng thái. Aggregate Reservation
 * chịu trách nhiệm kiểm tra các bước chuyển hợp lệ.
 */
public enum ReservationStatus {

    /**
     * Giữ chỗ tạm thời trong lúc khách thanh toán theo BR-103.
     *
     * <p>Bắt buộc có thời điểm hết hạn sau thời điểm tạo.
     * Khi quá hạn nhưng chưa được job chuyển sang RELEASED,
     * bản ghi vẫn chặn lịch.
     */
    HELD,

    /**
     * Chỗ giữ đã được xác nhận sau khi cọc được xác nhận.
     * Xe chưa được bàn giao cho khách.
     */
    CONFIRMED,

    /**
     * Xe đã được bàn giao và đang được sử dụng trong chuyến thuê.
     */
    IN_USE,

    /**
     * Xe bị khóa vì một nguyên nhân vận hành không phải đơn thuê.
     *
     * <p>Khóa vận hành đi thẳng vào trạng thái này,
     * không đi qua HELD.
     */
    BLOCKED,

    /**
     * Chỗ giữ hoặc chỗ đã xác nhận được giải phóng.
     *
     * <p>Áp dụng khi giữ chỗ hết hạn theo BR-103 hoặc đơn bị hủy
     * theo BR-304. Đây là trạng thái cuối, không còn chặn lịch.
     */
    RELEASED,

    /**
     * Chuyến thuê hoặc công việc vận hành đã hoàn tất.
     *
     * <p>Đây là trạng thái cuối nhưng vẫn bảo vệ khoảng đã lưu,
     * bao gồm khoảng đệm của lượt thuê. Chuyển sang COMPLETED
     * không tự thu ngắn hoặc xóa khoảng khóa.
     */
    COMPLETED
}