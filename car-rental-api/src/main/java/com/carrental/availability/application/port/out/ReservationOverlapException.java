package com.carrental.availability.application.port.out;

import java.io.Serial;

/**
 * Báo thao tác ghi khóa lịch bị từ chối do chồng thời gian trên cùng một xe.
 *
 * <p>Đây là lỗi thuộc hợp đồng cổng ghi theo BR-104 và ADR-0005.
 * Persistence adapter chỉ phát sinh lỗi này khi xác định đủ SQLSTATE
 * 23P01, schema availability, bảng reservation và ràng buộc
 * reservation_no_overlap.
 *
 * <p>Application service chuyển lỗi này thành lỗi nghiệp vụ
 * VEHICLE_NOT_AVAILABLE. Không dùng ngoại lệ này cho lỗi trùng mã,
 * lỗi CHECK, lỗi kết nối hoặc lỗi lưu trữ khác.
 *
 * <p>Lớp không phụ thuộc JDBC, PostgreSQL hoặc HTTP.
 */
public final class ReservationOverlapException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Tạo lỗi trùng lịch và giữ nguyên nguyên nhân để chẩn đoán nội bộ.
     *
     * <p>Không đưa trực tiếp thông tin lỗi lưu trữ vào phản hồi cho khách.
     *
     * @param cause lỗi lưu trữ gốc xác nhận vi phạm ràng buộc chống trùng lịch
     */
    public ReservationOverlapException(Throwable cause) {
        super(
                "Reservation overlaps an existing blocking period for the vehicle.",
                cause
        );
    }
}