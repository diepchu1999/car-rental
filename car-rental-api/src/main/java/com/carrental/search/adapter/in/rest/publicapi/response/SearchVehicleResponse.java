package com.carrental.search.adapter.in.rest.publicapi.response;

import com.carrental.search.application.view.SearchBranchSummary;
import com.carrental.search.application.view.SearchVehicleListItem;

/** Dữ liệu xe công khai BR-110/112/125: không ID nội bộ, biển số, loại sở hữu hoặc giá chưa triển khai. */
public record SearchVehicleResponse(String code, String make, String model, int seats,
        String transmission, String fuelType, boolean collateralFree,
        BranchResponse branch, double distanceMeters) {
    /** Ánh xạ tường minh, không serialize trực tiếp application view để tránh lộ vehicleId. */
    public static SearchVehicleResponse fromDomain(SearchVehicleListItem item) {
        return new SearchVehicleResponse(item.code(), item.make(), item.model(), item.seats(),
                item.transmission(), item.fuelType(), item.collateralFree(),
                BranchResponse.fromDomain(item.branch()), item.distanceMeters());
    }

    /** Thông tin nơi nhận xe theo BR-003/808; không trả khóa chính của chi nhánh. */
    public record BranchResponse(String code, String name, String address) {
        /** Chuyển dữ liệu hiển thị chi nhánh, giữ nguyên tên và địa chỉ. */
        public static BranchResponse fromDomain(SearchBranchSummary branch) {
            return new BranchResponse(branch.code(), branch.name(), branch.address());
        }
    }
}
