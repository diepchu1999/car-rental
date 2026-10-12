package com.carrental.vehicle.application.service;

import com.carrental.vehicle.application.port.in.GetActiveVehiclePriceUseCase;
import com.carrental.vehicle.application.port.out.ReadVehiclePort;
import com.carrental.vehicle.application.query.GetVehicleQuery;
import com.carrental.vehicle.application.view.VehiclePriceDetail;
import com.carrental.vehicle.domain.VehicleStatus;
import com.carrental.shared.validation.Validations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

/** Cung cấp giá xe ACTIVE theo BR-010/234; tái dùng cổng đọc, không tra chi nhánh không cần thiết. */
@Service
class VehiclePricingQueryService implements GetActiveVehiclePriceUseCase {
    private final ReadVehiclePort reads;

    /** Nhận nguồn đọc danh mục do vehicle sở hữu. */
    VehiclePricingQueryService(ReadVehiclePort reads) { this.reads = reads; }

    /** Rỗng chỉ cho thiếu/chưa ACTIVE; ACTIVE thiếu giá là lỗi toàn vẹn, không che thành 404. */
    @Override
    @Transactional(readOnly = true)
    public Optional<VehiclePriceDetail> getActivePrice(GetVehicleQuery query) {
        Validations.required(query, "query");
        return reads.findByCode(query.code()).filter(detail -> detail.status() == VehicleStatus.ACTIVE)
                .map(detail -> {
                    if (detail.dailyRate() == null) {
                        throw new IllegalStateException("Active vehicle is missing its daily rate.");
                    }
                    return new VehiclePriceDetail(detail.code(), detail.dailyRate());
                });
    }
}
