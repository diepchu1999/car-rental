package com.carrental.vehicle.application.port.in;

import com.carrental.vehicle.application.command.ChangeVehicleDailyRateCommand;
import com.carrental.vehicle.application.view.VehicleDetail;

/** Cổng đổi giá ngày xe theo BR-234, không tác động báo giá hoặc đơn đã tạo. */
public interface ChangeVehicleDailyRateUseCase {
    /** Đổi giá và đọc lại chi tiết xe trong cùng transaction. */
    VehicleDetail changeDailyRate(ChangeVehicleDailyRateCommand command);
}
