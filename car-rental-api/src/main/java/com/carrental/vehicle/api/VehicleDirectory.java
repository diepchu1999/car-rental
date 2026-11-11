package com.carrental.vehicle.api;

import java.util.Optional;

/** Cổng cung cấp xe đã lên sàn cho bước đặt đơn, không kiểm xe trống hoặc giữ lịch (BR-010/102). */
public interface VehicleDirectory {
    /**
     * Trả định danh để giữ chỗ và thuộc tính để kiểm GPLX (BR-106b/128), chi nhánh nhận/trả (BR-105).
     * Chỉ ACTIVE được trả ra; thiếu xe hoặc chưa ACTIVE đều rỗng. Lỗi toàn vẹn không đổi thành rỗng.
     * Cổng nội bộ không đòi vai nhân viên; use case bên gọi phải kiểm danh tính/quyền khách.
     */
    Optional<VehicleRef> findActiveByCode(String code);
}
