package com.carrental.branch.adapter.out.persistence;

import com.carrental.branch.application.view.BranchDetail;
import org.springframework.jdbc.core.RowMapper;

/**
 * Cung cấp bộ ánh xạ kết quả truy vấn chi nhánh sang mô hình đọc của application.
 *
 * <p>Chỉ chuyển dữ liệu từ kết quả JDBC, không thực hiện
 * quy tắc nghiệp vụ và không tạo HTTP response.
 */
final class BranchRowMappers {

    /**
     * Ánh xạ dòng hiện tại sang thông tin chi tiết chi nhánh.
     *
     * <p>Các tên cột phải khớp với kết quả truy vấn:
     * id, code, latitude và longitude.
     *
     * <p>Spring quản lý việc duyệt và đóng kết quả truy vấn.
     * Bộ ánh xạ không tự chuyển dòng hoặc đóng ResultSet.
     */
    static final RowMapper<BranchDetail> DETAIL =
            (resultSet, rowNumber) -> new BranchDetail(
                    resultSet.getLong("id"),
                    resultSet.getString("code"),
                    resultSet.getDouble("latitude"),
                    resultSet.getDouble("longitude")
            );

    /**
     * Ngăn khởi tạo đối tượng vì lớp chỉ cung cấp bộ ánh xạ dùng chung.
     */
    private BranchRowMappers() {
    }
}