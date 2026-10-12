package com.carrental.vehicle.api;

import java.util.Optional;

/** Cổng đọc giá xe lên sàn theo BR-010/234, không kiểm hoặc giữ lịch xe. */
public interface VehiclePricingDirectory {
    /** Xe không tồn tại hoặc không ACTIVE đều trả rỗng; lỗi lưu trữ phải truyền ra ngoài. */
    Optional<VehiclePricingRef> findActiveByCode(String code);
}
