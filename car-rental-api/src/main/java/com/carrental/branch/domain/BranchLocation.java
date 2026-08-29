package com.carrental.branch.domain;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.validation.Validations;

/**
 * Biểu diễn tọa độ địa lý bất biến của một chi nhánh.
 *
 * <p>Theo BR-003, vị trí của xe công ty khi tìm kiếm là vị trí
 * chi nhánh. Cách lưu tọa độ bằng PostGIS thuộc persistence adapter,
 * không thuộc đối tượng domain này.
 *
 * <p>Thứ tự tham số luôn là vĩ độ trước, kinh độ sau.
 * Giá trị được giữ nguyên, không tự làm tròn hoặc chuyển đổi.
 *
 * @param latitude  vĩ độ tính bằng độ, thuộc khoảng [-90, 90]
 * @param longitude kinh độ tính bằng độ, thuộc khoảng [-180, 180]
 */
public record BranchLocation(
        double latitude,
        double longitude
) {

    /**
     * Bảo đảm tọa độ hữu hạn và nằm trong giới hạn địa lý hợp lệ.
     *
     * <p>Kiểm tra ngay trong constructor để cả việc khởi tạo trực tiếp
     * lẫn việc tạo qua factory đều giữ cùng một bất biến.
     *
     * <p>Các giá trị biên và tọa độ bằng 0 đều được chấp nhận.
     *
     * @throws DomainException nếu một tọa độ là NaN, vô cực
     *                         hoặc nằm ngoài giới hạn hợp lệ
     */
    public BranchLocation {
        if (!Double.isFinite(latitude)
                || latitude < -90.0
                || latitude > 90.0) {
            throw DomainException.invalidInput(
                    "latitude must be a finite number between -90 and 90."
            );
        }

        if (!Double.isFinite(longitude)
                || longitude < -180.0
                || longitude > 180.0) {
            throw DomainException.invalidInput(
                    "longitude must be a finite number between -180 and 180."
            );
        }
    }

    /**
     * Tạo tọa độ từ các giá trị đầu vào có thể chưa được cung cấp.
     *
     * <p>Dùng Double để nhận biết null và báo lỗi thiếu dữ liệu
     * trước khi chuyển sang double. Không thay tọa độ thiếu bằng 0.
     *
     * @param latitude vĩ độ đầu vào, có thể null nếu bị thiếu
     * @param longitude kinh độ đầu vào, có thể null nếu bị thiếu
     * @return tọa độ có đủ hai thành phần và đã được kiểm tra
     * @throws DomainException nếu thiếu tọa độ hoặc tọa độ không hợp lệ
     */
    public static BranchLocation from(
            Double latitude,
            Double longitude
    ) {
        double requiredLatitude = Validations.required(
                latitude,
                "latitude"
        );
        double requiredLongitude = Validations.required(
                longitude,
                "longitude"
        );

        return new BranchLocation(
                requiredLatitude,
                requiredLongitude
        );
    }
}
