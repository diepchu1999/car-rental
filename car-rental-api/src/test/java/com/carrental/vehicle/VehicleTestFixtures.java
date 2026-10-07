package com.carrental.vehicle;

import com.carrental.vehicle.domain.Transmission;
import com.carrental.vehicle.domain.VehicleSpecifications;

/** Dữ liệu hợp lệ BR-018 cho test cũ; không phải giá trị mặc định của ứng dụng. */
public final class VehicleTestFixtures {
    /** Bộ thuộc tính cố định để các test khác tập trung vào quy tắc đang kiểm. */
    public static final VehicleSpecifications SPECIFICATIONS =
            new VehicleSpecifications(5, Transmission.AUTOMATIC, "Toyota", "Vios");

    /** Không khởi tạo lớp chứa dữ liệu test dùng chung. */
    private VehicleTestFixtures() {
    }
}
