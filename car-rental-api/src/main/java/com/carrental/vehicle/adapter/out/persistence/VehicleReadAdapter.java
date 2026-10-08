package com.carrental.vehicle.adapter.out.persistence;

import com.carrental.shared.sql.SqlLoader;
import com.carrental.vehicle.application.port.out.ReadVehiclePort;
import com.carrental.vehicle.application.view.VehicleDetail;
import com.carrental.vehicle.application.view.VehicleSearchCandidate;
import com.carrental.vehicle.application.query.ListSearchVehiclesQuery;
import com.carrental.vehicle.domain.Vehicle;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.sql.Types;
import java.util.Optional;

/**
 * Hiện thực cổng đọc xe bằng Native SQL và JDBC.
 *
 * <p>Adapter chỉ truy xuất và ánh xạ dữ liệu.
 * Không kiểm điều kiện duyệt, không đổi trạng thái
 * và không truy cập trực tiếp bảng của module khác.
 *
 * <p>Việc chuyển kết quả không tìm thấy thành lỗi nghiệp vụ
 * và quản lý ranh giới transaction thuộc application service.
 */
@Repository
class VehicleReadAdapter implements ReadVehiclePort {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final String findByCodeSql;
    private final String findSearchCandidatesSql;

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
    VehicleReadAdapter(
            NamedParameterJdbcTemplate jdbcTemplate,
            SqlLoader sqlLoader
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.findSearchCandidatesSql = sqlLoader.load(VehicleSqlPaths.FIND_SEARCH_CANDIDATES);
        this.findByCodeSql = sqlLoader.load(
                VehicleSqlPaths.FIND_BY_CODE
        );
    }

    /**
     * Đọc ứng viên bằng một câu SQL tham số hóa, không truy vấn từng xe.
     * Kiểu JDBC tường minh cho phép PostgreSQL xử lý bộ lọc null mà không phải đoán kiểu.
     */
    @Override
    public List<VehicleSearchCandidate> findSearchCandidates(ListSearchVehiclesQuery query) {
        if (query.branchIds().isEmpty()) {
            return List.of();
        }
        var parameters = new MapSqlParameterSource()
                .addValue("branch_ids", query.branchIds())
                .addValue("seats", query.seats(), Types.INTEGER)
                .addValue("transmission", query.transmission() == null ? null : query.transmission().name(), Types.VARCHAR)
                .addValue("fuel_type", query.fuelType() == null ? null : query.fuelType().name(), Types.VARCHAR)
                .addValue("make", query.make(), Types.VARCHAR)
                .addValue("model", query.model(), Types.VARCHAR);
        return List.copyOf(jdbcTemplate.query(findSearchCandidatesSql, parameters,
                VehicleRowMappers.SEARCH_CANDIDATE));
    }

    /**
     * Tra cứu xe theo mã nghiệp vụ và ánh xạ kết quả sang view.
     *
     * <p>Mã được truyền bằng tham số truy vấn, không nối vào chuỗi SQL.
     * Giá trị mã được giữ nguyên, không cắt khoảng trắng hoặc đổi kiểu chữ.
     *
     * <p>Không tìm thấy trả Optional rỗng.
     * Lỗi truy vấn hoặc ánh xạ dữ liệu phải được truyền ra ngoài,
     * không chuyển thành kết quả không tìm thấy.
     *
     * @param code mã nghiệp vụ cần tìm, không được null hoặc trắng
     * @return chi tiết xe nếu tìm thấy; Optional rỗng nếu không có
     * @throws DataAccessException nếu truy vấn gặp lỗi JDBC
     *                             hoặc kết quả có nhiều hơn một dòng
     * @throws IllegalArgumentException nếu giá trị phân loại trong CSDL
     *                                  không chuyển được sang enum tương ứng
     */
    @Override
    public Optional<VehicleDetail> findByCode(String code) {
        MapSqlParameterSource parameters =
                new MapSqlParameterSource("code", code);

        List<VehicleDetail> vehicles = jdbcTemplate.query(
                findByCodeSql,
                parameters,
                VehicleRowMappers.DETAIL
        );

        return DataAccessUtils.optionalResult(vehicles);
    }

    /**
     * Tải aggregate trực tiếp bằng mapper dành riêng cho đường ghi.
     *
     * <p>Dùng chung câu SQL hiện tại với đường đọc view,
     * nhưng không gọi findByCode hoặc mapper DETAIL.
     *
     * <p>Mã được truyền bằng tham số, giữ nguyên đầu vào.
     * Không tìm thấy trả Optional rỗng; lỗi truy xuất và khôi phục
     * aggregate được truyền ra ngoài.
     *
     * @param code mã nghiệp vụ cần tải, không được null hoặc trắng
     * @return aggregate nếu tìm thấy; Optional rỗng nếu không có
     */
    @Override
    public Optional<Vehicle> loadAggregate(String code) {
        MapSqlParameterSource parameters =
                new MapSqlParameterSource("code", code);

        List<Vehicle> vehicles = jdbcTemplate.query(
                findByCodeSql,
                parameters,
                VehicleRowMappers.AGGREGATE
        );

        return DataAccessUtils.optionalResult(vehicles);
    }
}
