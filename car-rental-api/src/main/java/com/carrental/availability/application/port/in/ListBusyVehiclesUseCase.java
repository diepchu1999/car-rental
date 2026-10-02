package com.carrental.availability.application.port.in;

import com.carrental.availability.application.query.ListBusyVehiclesQuery;

import java.util.Set;

/** Cổng đọc nội bộ phục vụ tra cứu xe bận theo BR-104, BR-109 và BR-116. */
public interface ListBusyVehiclesUseCase {

    /**
     * Tìm xe bận sau khi cộng đệm đúng một lần vào cuối khoảng thuê.
     *
     * <p>Kết quả không giữ chỗ và không thay thế ràng buộc chống trùng lịch.
     * HELD hết hạn chưa được nhả vẫn tính bận theo BR-103.
     *
     * @param query đầu vào đã kiểm tra, không null
     * @return tập ID xe bận chỉ thuộc tập ứng viên, không trùng
     */
    Set<Long> listBusyVehicleIds(ListBusyVehiclesQuery query);
}
