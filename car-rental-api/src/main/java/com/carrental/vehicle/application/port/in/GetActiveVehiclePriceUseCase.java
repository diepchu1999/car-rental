package com.carrental.vehicle.application.port.in;

import com.carrental.vehicle.application.query.GetVehicleQuery;
import com.carrental.vehicle.application.view.VehiclePriceDetail;
import java.util.Optional;

/** Chức năng đọc giá phục vụ báo giá BR-234, không phụ thuộc pricing. */
public interface GetActiveVehiclePriceUseCase {
    /** Chỉ trả giá khi xe ACTIVE, không lộ trạng thái của hồ sơ chưa lên sàn. */
    Optional<VehiclePriceDetail> getActivePrice(GetVehicleQuery query);
}
