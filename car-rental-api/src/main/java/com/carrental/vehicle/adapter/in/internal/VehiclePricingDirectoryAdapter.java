package com.carrental.vehicle.adapter.in.internal;

import com.carrental.vehicle.api.VehiclePricingDirectory;
import com.carrental.vehicle.api.VehiclePricingRef;
import com.carrental.vehicle.application.port.in.GetActiveVehiclePriceUseCase;
import com.carrental.vehicle.application.query.GetVehicleQuery;
import org.springframework.stereotype.Component;
import java.util.Optional;

/** Lối vào cross-module BR-234; chỉ gọi port/in và ánh xạ hợp đồng riêng theo ADR-0008. */
@Component
class VehiclePricingDirectoryAdapter implements VehiclePricingDirectory {
    private final GetActiveVehiclePriceUseCase useCase;

    /** Không nhảy trực tiếp tới JDBC hoặc service nội bộ. */
    VehiclePricingDirectoryAdapter(GetActiveVehiclePriceUseCase useCase) { this.useCase = useCase; }

    /** Giữ mã đầu vào, chỉ xuất mã và giá, không truyền loại sở hữu hoặc read view nội bộ. */
    @Override
    public Optional<VehiclePricingRef> findActiveByCode(String code) {
        return useCase.getActivePrice(GetVehicleQuery.from(code))
                .map(detail -> new VehiclePricingRef(detail.code(), detail.dailyRate()));
    }
}
