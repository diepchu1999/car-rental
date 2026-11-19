package com.carrental.vehicle.application.service;

import com.carrental.branch.api.BranchDirectory;
import com.carrental.shared.validation.Validations;
import com.carrental.vehicle.application.port.in.GetActiveVehicleForBookingUseCase;
import com.carrental.vehicle.application.port.out.ReadVehiclePort;
import com.carrental.vehicle.application.query.GetVehicleQuery;
import com.carrental.vehicle.application.view.VehicleBookingDetail;
import com.carrental.vehicle.domain.VehicleStatus;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Đọc xe ACTIVE và tra mã chi nhánh qua đúng cổng sở hữu; không JOIN chéo schema (ADR-0008). */
@Service
class VehicleBookingQueryService implements GetActiveVehicleForBookingUseCase {
    private final ReadVehiclePort reads;
    private final BranchDirectory branches;

    /** Tái dùng cổng đọc danh mục, không phụ thuộc use case hồ sơ admin hoặc CallerDirectory. */
    VehicleBookingQueryService(ReadVehiclePort reads, BranchDirectory branches) {
        this.reads = reads;
        this.branches = branches;
    }

    /** BR-010/105/128: thiếu dữ liệu bắt buộc ở xe ACTIVE là lỗi nội bộ, không đoán giá trị. */
    @Override
    @Transactional(readOnly = true)
    public Optional<VehicleBookingDetail> getActiveForBooking(GetVehicleQuery query) {
        Validations.required(query, "query");
        return reads.findByCode(query.code()).filter(detail -> detail.status() == VehicleStatus.ACTIVE)
                .map(detail -> {
                    if (detail.branchId() == null || detail.seats() == null || detail.transmission() == null) {
                        throw new IllegalStateException("Active vehicle is missing required booking attributes.");
                    }
                    var enriched = VehicleDetailEnricher.enrich(detail, branches);
                    return new VehicleBookingDetail(enriched.id(), enriched.code(), enriched.branchCode(),
                            enriched.seats(), enriched.transmission());
                });
    }
}
