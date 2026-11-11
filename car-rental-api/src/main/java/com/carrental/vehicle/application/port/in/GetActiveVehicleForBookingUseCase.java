package com.carrental.vehicle.application.port.in;

import com.carrental.vehicle.application.query.GetVehicleQuery;
import com.carrental.vehicle.application.view.VehicleBookingDetail;
import java.util.Optional;

/** Lối đọc xe lên sàn cho đặt đơn, tách khỏi quyền đọc hồ sơ /admin (BR-010/810). */
public interface GetActiveVehicleForBookingUseCase {
    /** Chỉ trả thuộc tính đã lưu; không đọc hoặc giữ lịch, không định giá lại báo giá. */
    Optional<VehicleBookingDetail> getActiveForBooking(GetVehicleQuery query);
}
