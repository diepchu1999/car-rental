package com.carrental.vehicle.application.query;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.validation.Validations;

/**
 * Chứa mã nghiệp vụ của xe cần đọc thông tin.
 *
 * <p>Query chỉ yêu cầu mã không thiếu hoặc trắng.
 * Không áp lại quy tắc định dạng mã khi tạo xe.
 *
 * <p>Application service thực hiện tra cứu và báo VEHICLE_NOT_FOUND
 * nếu mã không tồn tại. Query không truy cập database.
 *
 * @param code mã nghiệp vụ của xe cần đọc
 */
public record GetVehicleQuery(String code) {

    /**
     * Bảo đảm query chứa mã có nội dung và giữ nguyên giá trị đầu vào.
     *
     * @throws DomainException nếu mã là null, rỗng hoặc chỉ chứa khoảng trắng
     */
    public GetVehicleQuery {
        code = Validations.requiredText(code, "code");
    }

    /**
     * Tạo query từ mã đầu vào mà không cắt khoảng trắng hoặc đổi kiểu chữ.
     *
     * @param code mã xe đầu vào, có thể null nếu bị thiếu
     * @return query đã được kiểm tra
     * @throws DomainException nếu mã không có nội dung
     */
    public static GetVehicleQuery from(String code) {
        return new GetVehicleQuery(code);
    }
}