package com.carrental.vehicle.application.port.out;

import com.carrental.vehicle.application.view.VehicleDetail;
import com.carrental.vehicle.application.view.VehicleSearchCandidate;
import com.carrental.vehicle.application.query.ListSearchVehiclesQuery;
import com.carrental.vehicle.domain.Vehicle;

import java.util.List;
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
     * Đọc xe ACTIVE theo tập chi nhánh và bộ lọc thuộc tính (BR-010/126).
     * Không lọc thế chấp tại persistence: application phân giải BR-209 trước khi lọc cờ.
     * Tập chi nhánh rỗng phải trả rỗng, không đọc toàn bộ bảng.
     */
    List<VehicleSearchCandidate> findSearchCandidates(ListSearchVehiclesQuery query);

    /**
     * Tìm chi tiết xe phục vụ hiển thị hoặc đọc lại sau khi ghi.
     *
     * <p>Không kiểm lại điều kiện duyệt khi đọc hồ sơ.
     * View được phép thay đổi theo nhu cầu hiển thị mà không
     * quyết định dữ liệu đầu vào cho việc khôi phục aggregate.
     * Persistence để branchCode null; application bổ sung qua branch.api,
     * không yêu cầu adapter đọc schema của module khác (ADR-0008).
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
