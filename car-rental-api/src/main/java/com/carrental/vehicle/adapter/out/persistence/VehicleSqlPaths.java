package com.carrental.vehicle.adapter.out.persistence;

/**
 * Tập trung đường dẫn tài nguyên SQL của persistence adapter xe.
 *
 * <p>Đường dẫn tính từ gốc classpath, không phải đường dẫn
 * tuyệt đối trên máy và không chứa tiền tố src/main/resources.
 */
final class VehicleSqlPaths {

    /** Truy vấn ứng viên tìm kiếm BR-010/126, chỉ đọc schema vehicle. */
    static final String FIND_SEARCH_CANDIDATES = "sql/vehicle/find_search_vehicles.sql";

    /**
     * Đường dẫn câu lệnh chèn xe mới.
     */
    static final String INSERT = "sql/vehicle/insert_vehicle.sql";

    /**
     * Đường dẫn câu truy vấn chi tiết xe theo mã nghiệp vụ.
     */
    static final String FIND_BY_CODE =
            "sql/vehicle/find_vehicle_by_code.sql";

    /**
     * Đường dẫn câu lệnh đổi trạng thái khi trạng thái cũ còn khớp.
     */
    static final String UPDATE_STATUS =
            "sql/vehicle/update_vehicle_status.sql";

    /**
     * Ngăn khởi tạo đối tượng vì lớp chỉ cung cấp các hằng đường dẫn.
     */
    private VehicleSqlPaths() {
    }
}
