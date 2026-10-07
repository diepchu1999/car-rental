package com.carrental.branch.adapter.out.persistence;

import com.carrental.branch.application.port.out.WriteBranchPort;
import com.carrental.branch.domain.Branch;
import com.carrental.shared.sql.SqlLoader;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.IncorrectUpdateSemanticsDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Hiện thực cổng ghi chi nhánh bằng Native SQL và JDBC.
 *
 * <p>Adapter nhận mã đã được application sinh và vị trí đã được domain
 * kiểm tra. Không tự sinh mã hoặc đọc trước để kiểm tra mã tồn tại.
 *
 * <p>Ranh giới transaction do application service quản lý.
 */
@Repository
class BranchWriteAdapter implements WriteBranchPort {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final String insertSql;

    /**
     * Khởi tạo adapter và tải câu lệnh chèn một lần.
     *
     * @param jdbcTemplate công cụ thực thi SQL với tham số có tên
     * @param sqlLoader bộ đọc tài nguyên SQL từ classpath
     * @throws IllegalStateException nếu không đọc được tài nguyên SQL
     *                               hoặc nội dung SQL chỉ chứa khoảng trắng
     */
    BranchWriteAdapter(
            NamedParameterJdbcTemplate jdbcTemplate,
            SqlLoader sqlLoader
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.insertSql = sqlLoader.load(BranchSqlPaths.INSERT);
    }

    /**
     * Chèn chi nhánh bằng mã nghiệp vụ và vị trí được cung cấp.
     *
     * <p>Câu SQL chỉ bỏ qua xung đột mã nghiệp vụ.
     * Không cập nhật chi nhánh đang tồn tại khi mã bị trùng.
     *
     * <p>Các lỗi khác được báo lỗi, không chuyển thành giá trị false.
     * Kết quả true không phải là xác nhận giao dịch đã commit.
     *
     * @param branch chi nhánh có mã và vị trí hợp lệ, không được null
     * @return true nếu chèn được một bản ghi mới;
     *         false chỉ khi không chèn do trùng mã nghiệp vụ
     * @throws DataAccessException nếu thao tác ghi thất bại
     *                             hoặc số dòng bị ảnh hưởng khác 0 và 1
     */
    @Override
    public boolean insert(Branch branch) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("code", branch.code())
                .addValue("name", branch.name())
                .addValue("address", branch.address())
                .addValue("latitude", branch.location().latitude())
                .addValue("longitude", branch.location().longitude());

        int affectedRows = jdbcTemplate.update(
                insertSql,
                parameters
        );

        if (affectedRows == 1) {
            return true;
        }

        if (affectedRows == 0) {
            return false;
        }

        throw new IncorrectUpdateSemanticsDataAccessException(
                "Expected to insert zero or one branch row, but got: "
                        + affectedRows
        );
    }
}
