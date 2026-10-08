package com.carrental.vehicle.application.port.in;

import com.carrental.vehicle.application.query.ListSearchVehiclesQuery;
import com.carrental.vehicle.application.view.VehicleSearchSummary;
import java.util.List;

/** Cổng đọc danh mục xe hiển thị được, chưa kiểm lịch theo BR-010/126. */
public interface ListSearchVehiclesUseCase {
    /** Trả danh sách bất biến đã lọc thuộc tính và phân giải thế chấp; không phân trang. */
    List<VehicleSearchSummary> list(ListSearchVehiclesQuery query);
}
