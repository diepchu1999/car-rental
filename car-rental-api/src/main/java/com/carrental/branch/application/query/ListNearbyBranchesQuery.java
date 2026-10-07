package com.carrental.branch.application.query;

import com.carrental.branch.domain.BranchLocation;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.validation.Validations;

/**
 * Đầu vào địa lý đã kiểm theo BR-003 và ADR-0007, đơn vị nội bộ là mét.
 * Mặc định và trần bán kính tìm kiếm BR-125 thuộc search, không đóng cứng ở branch.
 * @param origin tọa độ tâm tìm kiếm
 * @param radiusMeters bán kính hữu hạn, dương
 */
public record ListNearbyBranchesQuery(BranchLocation origin, double radiusMeters) {
    /** Kiểm cả constructor trực tiếp, không để NaN hoặc vô cực tới PostGIS. */
    public ListNearbyBranchesQuery {
        origin = Validations.required(origin, "origin");
        if (!Double.isFinite(radiusMeters) || radiusMeters <= 0) {
            throw DomainException.invalidInput("radiusMeters must be a finite number greater than zero.");
        }
    }

    /** Tạo query từ tọa độ và bán kính, không thay giá trị thiếu bằng số không. */
    public static ListNearbyBranchesQuery from(Double latitude, Double longitude, Double radiusMeters) {
        return new ListNearbyBranchesQuery(BranchLocation.from(latitude, longitude),
                Validations.required(radiusMeters, "radiusMeters"));
    }
}
