package com.carrental.vehicle.application.port.out;

import com.carrental.vehicle.domain.Vehicle;
import com.carrental.vehicle.domain.VehicleStatus;

/**
 * Cổng đầu ra cho việc ghi dữ liệu xe.
 *
 * <p>Nhận aggregate hoặc các giá trị cần ghi,
 * không nhận read view theo quy tắc kiến trúc R4.
 *
 * <p>Mã nghiệp vụ và biển số được bảo vệ bằng ràng buộc duy nhất
 * theo database-guideline mục 2 và mục 8.
 *
 * <p>Application service sở hữu ranh giới transaction.
 * Phần hiện thực cổng không tự commit hoặc mở giao dịch độc lập.
 */
public interface WriteVehiclePort {

    /**
     * Chèn một xe mới với mã nghiệp vụ đã được application sinh.
     *
     * <p>Việc chống trùng được database bảo vệ nguyên tử,
     * không dựa vào truy vấn kiểm tra trước rồi mới chèn.
     *
     * <p>Nếu trùng mã nghiệp vụ, không chèn và không cập nhật
     * bản ghi đang tồn tại. Service có thể sinh mã khác để thử lại.
     *
     * <p>Trùng biển số phải được báo bằng VehiclePlateConflictException,
     * không được chuyển thành false hoặc xử lý như trùng mã.
     *
     * <p>Các lỗi lưu trữ khác phải được truyền ra ngoài.
     * Kết quả true xác nhận thao tác chèn trong transaction hiện tại,
     * không có nghĩa transaction đã được commit.
     *
     * @param vehicle xe cần chèn, không được null
     * @return true nếu chèn được một bản ghi mới;
     *         false chỉ khi không chèn do trùng mã nghiệp vụ
     * @throws VehiclePlateConflictException nếu database xác nhận
     *                                       vi phạm ràng buộc duy nhất của biển số
     */
    boolean insert(Vehicle vehicle);

    /**
     * Cập nhật trạng thái khi bản ghi vẫn ở trạng thái được mong đợi.
     *
     * <p>Điều kiện mã xe và trạng thái cũ phải được kiểm
     * trong cùng câu lệnh cập nhật ở database.
     * Không tách thành kiểm tra trước rồi cập nhật vô điều kiện.
     *
     * <p>Service phải gọi hành vi domain để xác định trạng thái mới.
     * Cổng ghi không tự quyết định chuyển trạng thái nghiệp vụ.
     *
     * <p>Chỉ cập nhật trạng thái, không ghi lại loại sở hữu,
     * biển số, chi nhánh hoặc giấy tờ.
     *
     * <p>False biểu thị không có bản ghi khớp cả mã và trạng thái cũ.
     * Lỗi database phải được báo lỗi, không chuyển thành false.
     *
     * <p>True chỉ xác nhận cập nhật trong transaction hiện tại,
     * không có nghĩa transaction đã được commit.
     *
     * @param code mã nghiệp vụ của xe, không được null hoặc trắng
     * @param expectedStatus trạng thái cũ bắt buộc phải còn khớp, không được null
     * @param newStatus trạng thái đích đã được domain cho phép, không được null
     * @return true nếu cập nhật được một bản ghi;
     *         false nếu không có bản ghi khớp điều kiện
     */
    boolean updateStatus(
            String code,
            VehicleStatus expectedStatus,
            VehicleStatus newStatus
    );
}