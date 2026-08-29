package com.carrental.vehicle.domain;

/**
 * Biểu diễn trạng thái vòng đời xe theo status-flow mục 4.
 *
 * <p>Luồng được hiện thực trong Task 4 là tạo bản nháp,
 * gửi duyệt và phê duyệt theo BR-010:
 * DRAFT → PENDING_APPROVAL → ACTIVE.
 *
 * <p>Enum khai báo tập trạng thái trong tài liệu,
 * không tự thực hiện hoặc cho phép chuyển trạng thái.
 * Điều kiện chuyển trạng thái thuộc hành vi của aggregate Vehicle.
 *
 * <p>ACTIVE không đồng nghĩa xe đang rảnh hoặc có thể đặt thuê
 * trong mọi khoảng thời gian. Kiểm tra lịch thuộc module availability.
 */
public enum VehicleStatus {

    /**
     * Hồ sơ xe đang ở dạng bản nháp, chưa gửi duyệt.
     */
    DRAFT,

    /**
     * Hồ sơ xe đã được gửi và đang chờ phê duyệt.
     */
    PENDING_APPROVAL,

    /**
     * Xe đã được phê duyệt và đang được khai thác.
     */
    ACTIVE,

    /**
     * Xe đang tạm ngừng khai thác.
     */
    INACTIVE,

    /**
     * Hồ sơ xe đã bị từ chối phê duyệt.
     */
    REJECTED,

    /**
     * Xe đã ngừng khai thác vĩnh viễn.
     */
    RETIRED
}