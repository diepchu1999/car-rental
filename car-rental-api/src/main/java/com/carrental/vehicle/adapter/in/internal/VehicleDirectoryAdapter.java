package com.carrental.vehicle.adapter.in.internal;

import com.carrental.vehicle.api.VehicleDirectory;
import com.carrental.vehicle.api.VehicleRef;
import com.carrental.vehicle.application.port.in.GetActiveVehicleForBookingUseCase;
import com.carrental.vehicle.application.query.GetVehicleQuery;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Ánh xạ cổng cross-module vào use case; không đọc JDBC hoặc gọi service hồ sơ admin trực tiếp. */
@Component
class VehicleDirectoryAdapter implements VehicleDirectory {
    private final GetActiveVehicleForBookingUseCase useCase;

    /** Nhận bean use case đã được Spring quản lý transaction. */
    VehicleDirectoryAdapter(GetActiveVehicleForBookingUseCase useCase) { this.useCase = useCase; }

    /** Xuất giá trị thuần qua API, không làm lộ enum domain, giấy tờ xe hoặc giá mới thay báo giá cũ. */
    @Override
    public Optional<VehicleRef> findActiveByCode(String code) {
        return useCase.getActiveForBooking(GetVehicleQuery.from(code))
                .map(detail -> new VehicleRef(detail.id(), detail.code(), detail.branchCode(),
                        detail.seats(), detail.transmission().name()));
    }
}
