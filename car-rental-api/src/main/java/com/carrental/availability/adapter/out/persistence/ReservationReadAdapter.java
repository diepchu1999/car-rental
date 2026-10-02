package com.carrental.availability.adapter.out.persistence;

import com.carrental.availability.application.port.out.ReadReservationPort;
import com.carrental.availability.domain.Reservation;
import com.carrental.availability.domain.ReservationPeriod;
import com.carrental.shared.sql.SqlLoader;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Hiện thực đọc aggregate và tra cứu xe bận bằng Native SQL theo ADR-0003, ADR-0005.
 *
 * <p>Không tính lại chính sách, đổi trạng thái hoặc tự mở transaction.
 * Application quyết định lỗi không tìm thấy và ranh giới transaction.
 */
@Repository
class ReservationReadAdapter implements ReadReservationPort {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final String findByCodeSql;
    private final String findBusyVehicleIdsSql;

    /**
     * Nhận JDBC và tải tài nguyên truy vấn một lần.
     *
     * @param jdbcTemplate công cụ truy vấn với tham số có tên
     * @param sqlLoader bộ đọc tài nguyên SQL
     */
    ReservationReadAdapter(NamedParameterJdbcTemplate jdbcTemplate, SqlLoader sqlLoader) {
        this.jdbcTemplate = jdbcTemplate;
        this.findByCodeSql = sqlLoader.load(ReservationSqlPaths.FIND_BY_CODE);
        this.findBusyVehicleIdsSql = sqlLoader.load(ReservationSqlPaths.FIND_BUSY_VEHICLE_IDS);
    }

    /**
     * Đọc nguyên trạng theo mã, bao gồm khóa đã hết hạn hoặc ở trạng thái cuối.
     *
     * <p>Mã được truyền bằng tham số, không nối vào SQL hoặc tự chuẩn hóa.
     * Chỉ trả rỗng khi không có dòng; lỗi truy vấn, ánh xạ hoặc nhiều dòng
     * đều được truyền ra ngoài, không coi là không tìm thấy.
     *
     * @param code mã reservation đã được application kiểm tra
     * @return aggregate nếu có, Optional rỗng nếu không có
     */
    @Override
    public Optional<Reservation> loadAggregate(String code) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("code", code, Types.VARCHAR);
        List<Reservation> reservations = jdbcTemplate.query(
                findByCodeSql, parameters, ReservationRowMappers.AGGREGATE
        );
        return DataAccessUtils.optionalResult(reservations);
    }

    /**
     * Truy vấn chồng khoảng bằng toán tử PostgreSQL trên đúng tập ứng viên.
     *
     * <p>Các ID và thời điểm đều được bind bằng tham số. Không chạy SQL với
     * danh sách rỗng để tránh IN (). Không lấy đồng hồ hay tự nhả HELD quá hạn.
     *
     * @param bufferedPeriod khoảng hữu hạn đã cộng đệm ở application
     * @param candidateIds các ID xe đã kiểm tra
     * @return tập ID xe bận không trùng; lỗi truy vấn được truyền ra ngoài
     */
    @Override
    public Set<Long> findBusyVehicleIds(
            ReservationPeriod bufferedPeriod, Collection<Long> candidateIds
    ) {
        if (candidateIds.isEmpty()) {
            return Set.of();
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("candidateIds", candidateIds, Types.BIGINT)
                .addValue("startInclusive", bufferedPeriod.startInclusive().atOffset(ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("endExclusive", bufferedPeriod.endExclusive().atOffset(ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE);
        return Set.copyOf(jdbcTemplate.queryForList(findBusyVehicleIdsSql, parameters, Long.class));
    }
}
