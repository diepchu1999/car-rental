package com.carrental.availability.adapter.out.persistence;

/**
 * Tập trung đường dẫn tài nguyên SQL của persistence adapter khóa lịch.
 *
 * <p>Đường dẫn tính từ gốc classpath, không chứa src/main/resources.
 */
final class ReservationSqlPaths {

    /** Đường dẫn câu lệnh chèn khóa lịch mới. */
    static final String INSERT = "sql/availability/insert_reservation.sql";

    /** Đường dẫn câu truy vấn aggregate theo mã reservation duy nhất. */
    static final String FIND_BY_CODE = "sql/availability/find_reservation_by_code.sql";

    /** Đường dẫn truy vấn xe bận trong tập ứng viên và khoảng đã cộng đệm. */
    static final String FIND_BUSY_VEHICLE_IDS = "sql/availability/find_busy_vehicle_ids.sql";

    /** Đường dẫn UPDATE trạng thái có điều kiện trạng thái cũ. */
    static final String UPDATE_STATUS = "sql/availability/update_reservation_status.sql";

    /** Đường dẫn nhả HELD quá hạn bằng một UPDATE có điều kiện. */
    static final String RELEASE_EXPIRED_HOLDS = "sql/availability/release_expired_holds.sql";

    /**
     * Ngăn khởi tạo vì lớp chỉ chứa các hằng đường dẫn.
     */
    private ReservationSqlPaths() {
    }
}
