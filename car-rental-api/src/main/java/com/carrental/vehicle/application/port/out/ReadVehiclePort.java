package com.carrental.vehicle.application.port.out;

import com.carrental.vehicle.application.view.VehicleDetail;
import com.carrental.vehicle.domain.Vehicle;

import java.util.Optional;

/**
 * Cổng đầu ra cho việc đọc dữ liệu xe.
 *
 * <p>Tách hai mục đích: lấy view để hiển thị và tải aggregate
 * để thực hiện hành vi nghiệp vụ.
 *
 * <p>Application không phụ thuộc JDBC hoặc cách tổ chức câu SQL.
 */
public interface ReadVehiclePort {

    /**
     * Tìm chi tiết xe phục vụ hiển thị hoặc đọc lại sau khi ghi.
     *
     * <p>Không kiểm lại điều kiện duyệt khi đọc hồ sơ.
     * View được phép thay đổi theo nhu cầu hiển thị mà không
     * quyết định dữ liệu đầu vào cho việc khôi phục aggregate.
     *
     * <p>Kết quả rỗng chỉ biểu thị không tìm thấy.
     * Lỗi truy xuất phải được truyền ra ngoài.
     *
     * @param code mã nghiệp vụ, không được null hoặc trắng
     * @return chi tiết xe nếu tìm thấy; Optional rỗng nếu không có;
     *         không bao giờ trả null
     */
    Optional<VehicleDetail> findByCode(String code);

    /**
     * Tải aggregate để thực hiện hành vi nghiệp vụ trên xe đã tồn tại.
     *
     * <p>Khôi phục trực tiếp từ dữ liệu lưu trữ, không dựng aggregate
     * từ VehicleDetail hoặc một read view khác.
     *
     * <p>Giữ trạng thái và giấy tờ đang lưu, không tự gửi duyệt
     * hoặc phê duyệt trong quá trình tải.
     *
     * <p>Kết quả rỗng chỉ biểu thị không tìm thấy.
     * Lỗi truy xuất hoặc khôi phục aggregate phải được truyền ra ngoài.
     *
     * @param code mã nghiệp vụ, không được null hoặRc trắng
     * @return aggregate nếu tìm thấy; Optional rỗng nếu không có;
     *         không bao giờ trả null
     */
    Optional<Vehicle> loadAggregate(String code);
}