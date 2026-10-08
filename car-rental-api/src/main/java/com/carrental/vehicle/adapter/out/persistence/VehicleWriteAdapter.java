package com.carrental.vehicle.adapter.out.persistence;

import com.carrental.shared.sql.SqlLoader;
import com.carrental.vehicle.application.port.out.VehiclePlateConflictException;
import com.carrental.vehicle.application.port.out.WriteVehiclePort;
import com.carrental.vehicle.domain.Vehicle;
import com.carrental.vehicle.domain.VehicleStatus;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.IncorrectUpdateSemanticsDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;

/**
 * Hiện thực cổng ghi xe bằng Native SQL và JDBC.
 *
 * <p>Lưu dữ liệu theo BR-001, BR-003, BR-005, BR-010 và BR-410.
 * Application sinh mã và domain quyết định chuyển trạng thái hợp lệ.
 * Adapter không tự sinh mã hoặc rẽ nhánh theo loại sở hữu.
 *
 * <p>Database bảo vệ tính duy nhất của mã và biển số.
 * Adapter chỉ chuyển đúng lỗi trùng biển số thành lỗi thuộc hợp đồng port.
 *
 * <p>Ranh giới transaction do application service quản lý.
 * Adapter không tự commit hoặc mở giao dịch độc lập.
 */
@Repository
class VehicleWriteAdapter implements WriteVehiclePort {

    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

    private static final String PLATE_NUMBER_CONSTRAINT =
            "uq_vehicle_plate_number";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final String insertSql;
    private final String updateStatusSql;

    /**
     * Khởi tạo adapter và tải các câu SQL một lần.
     *
     * @param jdbcTemplate công cụ thực thi SQL với tham số có tên
     * @param sqlLoader bộ đọc tài nguyên SQL từ classpath
     * @throws IllegalStateException nếu không đọc được tài nguyên SQL
     *                               hoặc nội dung SQL chỉ chứa khoảng trắng
     */
    VehicleWriteAdapter(
            NamedParameterJdbcTemplate jdbcTemplate,
            SqlLoader sqlLoader
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.insertSql = sqlLoader.load(VehicleSqlPaths.INSERT);
        this.updateStatusSql = sqlLoader.load(
                VehicleSqlPaths.UPDATE_STATUS
        );
    }

    /**
     * Chèn xe với dữ liệu đã được application và domain chuẩn bị.
     *
     * <p>Trùng mã nghiệp vụ trả false để application có thể sinh mã khác.
     * Không cập nhật bản ghi đang tồn tại và không đọc trước để kiểm trùng.
     *
     * <p>Chỉ lỗi duy nhất của biển số được chuyển thành
     * VehiclePlateConflictException. Các lỗi lưu trữ khác được truyền nguyên.
     *
     * <p>Kết quả true không có nghĩa transaction đã được commit.
     *
     * @param vehicle xe cần lưu, không được null
     * @return true nếu chèn được một dòng; false nếu bị trùng mã nghiệp vụ
     * @throws VehiclePlateConflictException nếu vi phạm tính duy nhất của biển số
     * @throws DataAccessException nếu có lỗi lưu trữ khác
     *                             hoặc số dòng bị ảnh hưởng khác 0 và 1
     */
    @Override
    public boolean insert(Vehicle vehicle) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("code", vehicle.code(), Types.VARCHAR)
                .addValue("seats", vehicle.specifications().seats(), Types.INTEGER)
                .addValue("transmission", vehicle.specifications().transmission().name(), Types.VARCHAR)
                .addValue("make", vehicle.specifications().make(), Types.VARCHAR)
                .addValue("model", vehicle.specifications().model(), Types.VARCHAR)
                .addValue(
                        "plateNumber",
                        vehicle.plateNumber(),
                        Types.VARCHAR
                )
                .addValue(
                        "ownershipType",
                        vehicle.ownershipType().name(),
                        Types.VARCHAR
                )
                .addValue(
                        "fuelType",
                        vehicle.fuelType().name(),
                        Types.VARCHAR
                )
                .addValue("branchId", vehicle.branchId(), Types.BIGINT)
                .addValue(
                        "status",
                        vehicle.status().name(),
                        Types.VARCHAR
                )
                .addValue(
                        "inspectionExpiresOn",
                        vehicle.documents().inspectionExpiresOn(),
                        Types.DATE
                )
                .addValue(
                        "liabilityInsuranceExpiresOn",
                        vehicle.documents().liabilityInsuranceExpiresOn(),
                        Types.DATE
                );

        try {
            int affectedRows = jdbcTemplate.update(
                    insertSql,
                    parameters
            );

            return wasSingleRowAffected(affectedRows, "insert");
        } catch (DataIntegrityViolationException failure) {
            if (isPlateNumberConflict(failure)) {
                throw new VehiclePlateConflictException(failure);
            }

            throw failure;
        }
    }

    /**
     * Cập nhật trạng thái khi mã xe và trạng thái cũ cùng khớp.
     *
     * <p>Điều kiện và thao tác ghi nằm trong cùng một câu SQL.
     * Chỉ thay đổi cột status, không ghi lại các thuộc tính khác.
     *
     * <p>Application phải gọi domain để xác định trạng thái đích
     * trước khi yêu cầu adapter lưu.
     *
     * <p>Kết quả false không phải lỗi truy vấn: nó cho biết
     * không còn bản ghi khớp cả mã và trạng thái được mong đợi.
     *
     * @param code mã xe cần cập nhật, không được null hoặc trắng
     * @param expectedStatus trạng thái cũ phải còn khớp, không được null
     * @param newStatus trạng thái đích đã được domain cho phép, không được null
     * @return true nếu cập nhật một dòng; false nếu không có dòng khớp
     * @throws DataAccessException nếu thao tác ghi thất bại
     *                             hoặc số dòng bị ảnh hưởng khác 0 và 1
     */
    @Override
    public boolean updateStatus(
            String code,
            VehicleStatus expectedStatus,
            VehicleStatus newStatus
    ) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("code", code, Types.VARCHAR)
                .addValue(
                        "expectedStatus",
                        expectedStatus.name(),
                        Types.VARCHAR
                )
                .addValue(
                        "newStatus",
                        newStatus.name(),
                        Types.VARCHAR
                );

        int affectedRows = jdbcTemplate.update(
                updateStatusSql,
                parameters
        );

        return wasSingleRowAffected(affectedRows, "update");
    }

    /**
     * Nhận diện đúng lỗi duy nhất của biển số bằng thông tin có cấu trúc.
     *
     * <p>Phải khớp SQLSTATE, schema, bảng và tên constraint.
     * Không phân tích chuỗi thông báo lỗi vì nội dung có thể thay đổi.
     *
     * <p>Nếu không đủ thông tin để xác nhận, trả false để bên gọi
     * truyền nguyên lỗi lưu trữ, không gán nhầm thành lỗi biển số.
     *
     * @param failure lỗi toàn vẹn dữ liệu do JDBC báo
     * @return true chỉ khi PostgreSQL xác nhận trùng biển số của bảng xe
     */
    private static boolean isPlateNumberConflict(
            DataIntegrityViolationException failure
    ) {
        if (!(failure.getMostSpecificCause()
                instanceof PSQLException postgresFailure)) {
            return false;
        }

        if (!UNIQUE_VIOLATION_SQL_STATE.equals(
                postgresFailure.getSQLState()
        )) {
            return false;
        }

        ServerErrorMessage details =
                postgresFailure.getServerErrorMessage();

        return details != null
                && "vehicle".equals(details.getSchema())
                && "vehicle".equals(details.getTable())
                && PLATE_NUMBER_CONSTRAINT.equals(
                details.getConstraint()
        );
    }

    /**
     * Chuyển số dòng bị tác động thành kết quả của cổng ghi.
     *
     * <p>Các câu SQL hiện tại chỉ được tác động tối đa một xe.
     * Số dòng khác 0 và 1 là tình huống bất thường phải báo lỗi.
     *
     * @param affectedRows số dòng JDBC trả về
     * @param operation tên thao tác nội bộ dùng trong thông báo chẩn đoán
     * @return true nếu tác động một dòng; false nếu không tác động dòng nào
     * @throws IncorrectUpdateSemanticsDataAccessException nếu số dòng bất thường
     */
    private static boolean wasSingleRowAffected(
            int affectedRows,
            String operation
    ) {
        if (affectedRows == 1) {
            return true;
        }

        if (affectedRows == 0) {
            return false;
        }

        throw new IncorrectUpdateSemanticsDataAccessException(
                "Expected to " + operation
                        + " zero or one vehicle row, but got: "
                        + affectedRows
        );
    }
}
