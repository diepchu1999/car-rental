package com.carrental.vehicle.application.port.in;

import com.carrental.vehicle.application.command.ChangeVehicleDailyRateCommand;
import com.carrental.vehicle.application.view.VehicleDetail;

/** Cổng đổi giá ngày xe theo BR-234/810, không tác động báo giá hoặc đơn đã tạo. */
public interface ChangeVehicleDailyRateUseCase {
    /**
     * Đổi giá và đọc lại chi tiết xe trong cùng transaction, ở mọi trạng thái xe.
     * Chỉ ADMIN hoặc BRANCH_MANAGER của chi nhánh có xe được gọi; sai vai/phạm vi trả ACCESS_DENIED,
     * MANAGER chưa gán trả STAFF_BRANCH_REQUIRED, mã xe không tồn tại trả VEHICLE_NOT_FOUND.
     *
     * @param command mã xe và giá ngày hợp lệ, không được null
     * @return chi tiết xe sau cập nhật, có mã chi nhánh thay cho ID nội bộ ở REST
     */
    VehicleDetail changeDailyRate(ChangeVehicleDailyRateCommand command);
}
