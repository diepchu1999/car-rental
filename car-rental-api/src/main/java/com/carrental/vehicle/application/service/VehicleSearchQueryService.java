package com.carrental.vehicle.application.service;

import com.carrental.vehicle.application.port.in.ListSearchVehiclesUseCase;
import com.carrental.vehicle.application.port.out.ReadVehiclePort;
import com.carrental.vehicle.application.query.ListSearchVehiclesQuery;
import com.carrental.vehicle.application.view.VehicleSearchCandidate;
import com.carrental.vehicle.application.view.VehicleSearchSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** Điều phối truy vấn BR-010/126 và phân giải thế chấp BR-209; không quyết định lịch xe trống. */
@Service
class VehicleSearchQueryService implements ListSearchVehiclesUseCase {
    private final ReadVehiclePort reads;
    private final CollateralPolicyResolver resolver;

    /** Nhận cổng đọc gộp của xe và resolver; không phụ thuộc JDBC hoặc module booking. */
    VehicleSearchQueryService(ReadVehiclePort reads, CollateralPolicyResolver resolver) {
        this.reads = reads;
        this.resolver = resolver;
    }

    /** Một lượt đọc ứng viên, một lần phân giải trên mỗi xe; không đọc từng xe gây N+1. */
    @Override
    @Transactional(readOnly = true)
    public List<VehicleSearchSummary> list(ListSearchVehiclesQuery query) {
        if (query.branchIds().isEmpty()) {
            return List.of();
        }
        return reads.findSearchCandidates(query).stream()
                .map(candidate -> summarize(candidate, query))
                .filter(view -> query.collateralFree() == null
                        || view.collateralFree() == query.collateralFree())
                .toList();
    }

    /** Loại thông tin sở hữu khỏi view ngay sau khi phân giải, trước khi lọc cờ miễn thế chấp. */
    private VehicleSearchSummary summarize(VehicleSearchCandidate candidate, ListSearchVehiclesQuery query) {
        var policy = resolver.resolve(candidate.ownershipType(), query.rentalType());
        return new VehicleSearchSummary(candidate.id(), candidate.code(), candidate.branchId(),
                candidate.seats(), candidate.transmission().name(), candidate.fuelType().name(),
                candidate.make(), candidate.model(), policy.collateralFree());
    }
}
