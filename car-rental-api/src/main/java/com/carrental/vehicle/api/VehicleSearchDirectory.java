package com.carrental.vehicle.api;

import com.carrental.shared.rental.RentalType;
import java.util.List;

/**
 * Cổng tra cứu xe hiển thị được theo BR-010/126, không tiết lộ loại sở hữu (BR-112).
 * Chưa kiểm lịch trống: bên điều phối phải hỏi availability trước khi hiển thị kết quả.
 */
public interface VehicleSearchDirectory {
    /**
     * Liệt kê xe ACTIVE thuộc tập chi nhánh; danh sách chi nhánh rỗng trả kết quả rỗng.
     * Bộ lọc null nghĩa là không lọc; chuỗi trắng hoặc enum không hợp lệ là lỗi đầu vào.
     * Hãng/dòng khớp toàn bộ, bỏ khoảng trắng đầu cuối và không phân biệt hoa thường.
     * Cờ thế chấp được phân giải trong vehicle theo BR-209, không dùng để suy loại sở hữu.
     *
     * @param branchIds định danh nội bộ chi nhánh
     * @param rentalType gói thuê để phân giải thế chấp
     * @param seats số chỗ tùy chọn
     * @param transmission MANUAL hoặc AUTOMATIC, tùy chọn
     * @param fuelType PETROL, DIESEL, ELECTRIC hoặc HYBRID, tùy chọn
     * @param make hãng xe tùy chọn
     * @param model dòng xe tùy chọn
     * @param collateralFree true lọc xe miễn, false lọc xe cần thế chấp, null không lọc
     * @return danh sách bất biến, chưa phân trang hay xếp theo khoảng cách
     * @throws IllegalStateException nếu gặp xe PARTNER chưa có chính sách được hỗ trợ
     */
    List<VehicleSearchView> list(List<Long> branchIds, RentalType rentalType,
            Integer seats, String transmission, String fuelType,
            String make, String model, Boolean collateralFree);
}
