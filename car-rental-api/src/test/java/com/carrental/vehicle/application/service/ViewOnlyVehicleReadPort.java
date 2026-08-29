package com.carrental.vehicle.application.service;

import com.carrental.vehicle.application.port.out.ReadVehiclePort;
import com.carrental.vehicle.application.view.VehicleDetail;
import com.carrental.vehicle.domain.Vehicle;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Mô phỏng cổng đọc cho các test chỉ được phép lấy view.
 *
 * <p>Dùng trong luồng tra cứu và đọc lại sau khi tạo xe.
 * Nếu service gọi đường tải aggregate, test phải thất bại ngay.
 *
 * <p>Lớp chỉ nằm trong source test, không được đóng gói vào ứng dụng.
 */
final class ViewOnlyVehicleReadPort implements ReadVehiclePort {

    private final Function<String, Optional<VehicleDetail>> findBehavior;

    /**
     * Nhận hành vi đọc view do từng kịch bản cung cấp.
     *
     * @param findBehavior hàm trả view, kết quả rỗng hoặc ném lỗi
     */
    ViewOnlyVehicleReadPort(
            Function<String, Optional<VehicleDetail>> findBehavior
    ) {
        this.findBehavior = Objects.requireNonNull(
                findBehavior,
                "findBehavior must not be null."
        );
    }

    /**
     * Chuyển yêu cầu đọc view cho hành vi của kịch bản.
     *
     * @param code mã xe cần đọc
     * @return kết quả do kịch bản cung cấp
     */
    @Override
    public Optional<VehicleDetail> findByCode(String code) {
        return findBehavior.apply(code);
    }

    /**
     * Từ chối tải aggregate trong kịch bản chỉ được phép đọc view.
     *
     * @param code mã xe được yêu cầu tải
     * @return không trả về vì phương thức luôn báo lỗi
     * @throws AssertionError nếu service gọi nhầm đường đọc
     */
    @Override
    public Optional<Vehicle> loadAggregate(String code) {
        throw new AssertionError(
                "Aggregate loading must not be called in a view-only scenario."
        );
    }
}