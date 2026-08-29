package com.carrental.branch.adapter.out.persistence;

import com.carrental.branch.application.port.out.ReadBranchPort;
import com.carrental.branch.application.view.BranchDetail;
import com.carrental.shared.sql.SqlLoader;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Hiện thực cổng đọc chi nhánh bằng Native SQL và JDBC.
 *
 * <p>Adapter chỉ truy xuất và ánh xạ dữ liệu.
 * Việc chuyển kết quả không tìm thấy thành lỗi nghiệp vụ thuộc service.
 *
 * <p>Ranh giới transaction do application service quản lý.
 */
@Repository
class BranchReadAdapter implements ReadBranchPort {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final String findByCodeSql;

    /**
     * Khởi tạo adapter và tải câu truy vấn một lần.
     *
     * <p>Nội dung SQL được giữ lại để sử dụng cho các lần gọi,
     * không đọc lại tài nguyên ở mỗi yêu cầu.
     *
     * @param jdbcTemplate công cụ thực thi SQL với tham số có tên
     * @param sqlLoader bộ đọc tài nguyên SQL từ classpath
     * @throws IllegalStateException nếu không đọc được tài nguyên SQL
     *                               hoặc nội dung SQL chỉ chứa khoảng trắng
     */
    BranchReadAdapter(
            NamedParameterJdbcTemplate jdbcTemplate,
            SqlLoader sqlLoader
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.findByCodeSql = sqlLoader.load(
                BranchSqlPaths.FIND_BY_CODE
        );
    }

    /**
     * Tra cứu chi nhánh theo mã nghiệp vụ và ánh xạ kết quả sang view.
     *
     * <p>Mã được truyền bằng tham số truy vấn, không nối vào chuỗi SQL.
     * Giá trị mã được giữ nguyên, không cắt khoảng trắng hoặc đổi kiểu chữ.
     *
     * <p>Không tìm thấy trả Optional rỗng. Lỗi truy vấn vẫn được báo lỗi,
     * không chuyển thành kết quả không tìm thấy.
     *
     * @param code mã nghiệp vụ cần tìm, không được null hoặc trắng
     * @return chi tiết chi nhánh nếu tìm thấy; Optional rỗng nếu không có
     * @throws DataAccessException nếu truy vấn hoặc ánh xạ dữ liệu thất bại,
     *                             hoặc kết quả có nhiều hơn một dòng
     */
    @Override
    public Optional<BranchDetail> findByCode(String code) {
        MapSqlParameterSource parameters =
                new MapSqlParameterSource("code", code);

        List<BranchDetail> branches = jdbcTemplate.query(
                findByCodeSql,
                parameters,
                BranchRowMappers.DETAIL
        );

        return DataAccessUtils.optionalResult(branches);
    }
}