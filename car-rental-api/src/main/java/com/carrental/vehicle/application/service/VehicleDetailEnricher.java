package com.carrental.vehicle.application.service;

import com.carrental.branch.api.BranchDirectory;
import com.carrental.vehicle.application.view.VehicleDetail;

/** Bổ sung mã chi nhánh qua cổng sở hữu theo BR-003 và ADR-0008, không suy mã từ ID. */
final class VehicleDetailEnricher {
    /** Tiện ích nội bộ application, không cần thêm bean hoặc truy cập persistence. */
    private VehicleDetailEnricher() {
    }

    /**
     * Không có liên kết thì trả mã null; tham chiếu có ID nhưng mất chi nhánh là lỗi toàn vẹn nội bộ.
     * Không biến lỗi nguồn dữ liệu thành mã null hoặc lỗi đầu vào của khách.
     */
    static VehicleDetail enrich(VehicleDetail detail, BranchDirectory branches) {
        if (detail.branchId() == null) {
            return detail.withBranchCode(null);
        }
        var branch = branches.findById(detail.branchId()).orElseThrow(() ->
                new IllegalStateException("Vehicle references a missing branch: " + detail.code()));
        return detail.withBranchCode(branch.code());
    }
}
