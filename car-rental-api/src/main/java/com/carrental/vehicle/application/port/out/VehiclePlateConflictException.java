package com.carrental.vehicle.application.port.out;

import java.io.Serial;

/**
 * Báo thao tác chèn xe bị từ chối vì xung đột biển số.
 *
 * <p>Đây là lỗi thuộc hợp đồng cổng ghi, tương ứng ràng buộc
 * UNIQUE trên plate_number theo database-guideline mục 8.
 *
 * <p>Persistence adapter chỉ phát sinh lỗi này khi xác định
 * đúng ràng buộc biển số bị vi phạm, không dùng cho mọi lỗi lưu trữ.
 *
 * <p>Application service chuyển lỗi này thành lỗi nghiệp vụ
 * VEHICLE_PLATE_ALREADY_EXISTS. Ngoại lệ không chứa mã HTTP
 * hoặc phụ thuộc lớp exception riêng của PostgreSQL.
 */
public final class VehiclePlateConflictException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Tạo lỗi xung đột biển số và giữ nguyên nguyên nhân lưu trữ.
     *
     * <p>Nguyên nhân phục vụ chẩn đoán nội bộ,
     * không được đưa trực tiếp vào response HTTP.
     *
     * @param cause lỗi lưu trữ gốc gây xung đột biển số
     */
    public VehiclePlateConflictException(Throwable cause) {
        super(
                "Vehicle plate number conflicts with an existing record.",
                cause
        );
    }
}