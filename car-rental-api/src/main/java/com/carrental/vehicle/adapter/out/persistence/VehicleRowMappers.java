package com.carrental.vehicle.adapter.out.persistence;

import com.carrental.vehicle.application.view.VehicleDetail;
import com.carrental.vehicle.domain.FuelType;
import com.carrental.vehicle.domain.OwnershipType;
import com.carrental.vehicle.domain.Vehicle;
import com.carrental.vehicle.domain.VehicleDocuments;
import com.carrental.vehicle.domain.VehicleStatus;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDate;

/**
 * Cung cấp hai bộ ánh xạ độc lập cho view và aggregate của xe.
 *
 * <p>Mỗi mapper đọc trực tiếp các cột JDBC cần cho mục đích của nó.
 * Mapper aggregate không gọi mapper view hoặc phụ thuộc VehicleDetail.
 *
 * <p>Không thực hiện gửi duyệt hoặc phê duyệt khi tải dữ liệu.
 * Các giá trị null của chi nhánh và ngày giấy tờ được giữ nguyên.
 */
final class VehicleRowMappers {

    /**
     * Ánh xạ dòng hiện tại sang thông tin chi tiết xe.
     *
     * <p>Tên cột phải khớp với câu truy vấn find_vehicle_by_code.sql.
     * Các chuỗi phân loại được chuyển trực tiếp sang enum tương ứng,
     * không rẽ nhánh theo loại sở hữu.
     *
     * <p>Spring quản lý việc duyệt và đóng kết quả truy vấn.
     * Bộ ánh xạ không tự chuyển dòng hoặc đóng ResultSet.
     */
    static final RowMapper<VehicleDetail> DETAIL =
            (resultSet, rowNumber) -> new VehicleDetail(
                    resultSet.getLong("id"),
                    resultSet.getString("code"),
                    resultSet.getString("plate_number"),
                    OwnershipType.valueOf(
                            resultSet.getString("ownership_type")
                    ),
                    FuelType.valueOf(
                            resultSet.getString("fuel_type")
                    ),
                    resultSet.getObject("branch_id", Long.class),
                    VehicleStatus.valueOf(
                            resultSet.getString("status")
                    ),
                    resultSet.getObject(
                            "inspection_expires_on",
                            LocalDate.class
                    ),
                    resultSet.getObject(
                            "liability_insurance_expires_on",
                            LocalDate.class
                    )
            );

    /**
     * Khôi phục aggregate trực tiếp từ dòng dữ liệu JDBC.
     *
     * <p>Dùng Vehicle.restore để giữ nguyên trạng thái đã lưu,
     * không tạo lại xe DRAFT và không kiểm điều kiện phê duyệt.
     *
     * <p>Giấy tờ thiếu hoặc đã hết hạn vẫn được tải.
     * Điều kiện BR-005 được domain kiểm khi gọi hành vi approve.
     *
     * <p>Không sử dụng DETAIL hoặc tạo VehicleDetail trung gian.
     * Loại sở hữu được chuyển sang enum, không rẽ nhánh theo BR-001.
     */
    static final RowMapper<Vehicle> AGGREGATE =
            (resultSet, rowNumber) -> Vehicle.restore(
                    resultSet.getString("code"),
                    resultSet.getString("plate_number"),
                    OwnershipType.valueOf(
                            resultSet.getString("ownership_type")
                    ),
                    FuelType.valueOf(
                            resultSet.getString("fuel_type")
                    ),
                    resultSet.getObject("branch_id", Long.class),
                    VehicleStatus.valueOf(
                            resultSet.getString("status")
                    ),
                    new VehicleDocuments(
                            resultSet.getObject(
                                    "inspection_expires_on",
                                    LocalDate.class
                            ),
                            resultSet.getObject(
                                    "liability_insurance_expires_on",
                                    LocalDate.class
                            )
                    )
            );

    /**
     * Ngăn khởi tạo đối tượng vì lớp chỉ cung cấp bộ ánh xạ dùng chung.
     */
    private VehicleRowMappers() {
    }
}