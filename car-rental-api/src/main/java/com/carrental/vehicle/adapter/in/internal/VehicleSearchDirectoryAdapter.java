package com.carrental.vehicle.adapter.in.internal;

import com.carrental.shared.rental.RentalType;
import com.carrental.vehicle.api.VehicleSearchDirectory;
import com.carrental.vehicle.api.VehicleSearchView;
import com.carrental.vehicle.application.port.in.ListSearchVehiclesUseCase;
import com.carrental.vehicle.application.query.ListSearchVehiclesQuery;
import org.springframework.stereotype.Component;
import java.util.List;

/** Lối vào cross-module theo ADR-0004/0008; không cho bên gọi nhận loại sở hữu (BR-112). */
@Component
class VehicleSearchDirectoryAdapter implements VehicleSearchDirectory {
    private final ListSearchVehiclesUseCase useCase;

    /** Chỉ phụ thuộc cổng vào, không nhảy trực tiếp tới persistence hoặc service. */
    VehicleSearchDirectoryAdapter(ListSearchVehiclesUseCase useCase) {
        this.useCase = useCase;
    }

    /** Kiểm giá trị thô rồi ánh xạ read view sang hợp đồng công khai riêng cho tìm kiếm. */
    @Override
    public List<VehicleSearchView> list(List<Long> branchIds, RentalType rentalType,
            Integer seats, String transmission, String fuelType,
            String make, String model, Boolean collateralFree) {
        var query = ListSearchVehiclesQuery.from(branchIds, rentalType, seats, transmission,
                fuelType, make, model, collateralFree);
        return useCase.list(query).stream().map(view -> new VehicleSearchView(
                view.id(), view.code(), view.branchId(), view.seats(), view.transmission(),
                view.fuelType(), view.make(), view.model(), view.collateralFree())).toList();
    }
}
