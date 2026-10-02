package com.carrental.availability.adapter.out.persistence;

import com.carrental.availability.application.port.out.ReservationOverlapException;
import com.carrental.availability.application.port.out.WriteReservationPort;
import com.carrental.availability.domain.Reservation;
import com.carrental.availability.domain.ReservationStatus;
import com.carrental.shared.sql.SqlLoader;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.dao.IncorrectUpdateSemanticsDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Hiện thực cổng ghi khóa lịch bằng Native SQL và JDBC.
 *
 * <p>Ghi thẳng theo BR-104, ADR-0005; không truy vấn trước để quyết định
 * xe còn trống. PostgreSQL bảo vệ mọi loại khóa bằng cùng một ràng buộc.
 *
 * <p>Application đã chuẩn bị mã, khoảng có đệm và hạn giữ chỗ.
 * Adapter không sinh mã, cộng đệm, đọc Clock hoặc tính lại chính sách.
 *
 * <p>Tham gia transaction do application quản lý.
 * Không tự commit hoặc mở transaction độc lập.
 */
@Repository
class ReservationWriteAdapter implements WriteReservationPort {

    private static final String EXCLUSION_VIOLATION_SQL_STATE = "23P01";
    private static final String DEADLOCK_SQL_STATE = "40P01";
    private static final int MAX_INSERT_ATTEMPTS = 3;
    private static final String OVERLAP_CONSTRAINT = "reservation_no_overlap";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final String insertSql;
    private final String updateStatusSql;
    private final String releaseExpiredHoldsSql;
    private final TransactionTemplate nestedInsert;

    /**
     * Khởi tạo adapter và tải SQL một lần.
     *
     * @param jdbcTemplate công cụ thực thi SQL với tham số có tên
     * @param sqlLoader bộ đọc tài nguyên SQL từ classpath
     * @param transactionManager bộ quản lý transaction JDBC, dùng savepoint trên transaction bên gọi
     * @throws IllegalStateException nếu không đọc được tài nguyên SQL hợp lệ
     */
    ReservationWriteAdapter(
            NamedParameterJdbcTemplate jdbcTemplate,
            SqlLoader sqlLoader,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.insertSql = sqlLoader.load(ReservationSqlPaths.INSERT);
        this.updateStatusSql = sqlLoader.load(ReservationSqlPaths.UPDATE_STATUS);
        this.releaseExpiredHoldsSql = sqlLoader.load(ReservationSqlPaths.RELEASE_EXPIRED_HOLDS);
        this.nestedInsert = new TransactionTemplate(transactionManager);
        this.nestedInsert.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    /**
     * Chèn khóa lịch và lấy ID do PostgreSQL sinh.
     *
     * <p>Không có dòng trả về nghĩa là SQL đã bỏ qua xung đột mã.
     * Trùng lịch được dịch thành ReservationOverlapException chỉ khi
     * khớp đầy đủ thông tin ràng buộc; các lỗi khác được truyền nguyên.
     *
     * <p>Mỗi INSERT chạy trong savepoint của transaction bên gọi. Chỉ SQLSTATE 40P01
     * được thử lại sau khi TransactionTemplate đã rollback savepoint, tối đa ba lần
     * gồm lần đầu. Giữ nguyên bộ tham số, không sinh mã hoặc tính lại hạn/khoảng.
     * Hết lượt ném lại chính exception deadlock đầu tiên, không giả thành xe đã bận.
     * Không thử lại lock timeout 55P03 hoặc lỗi lưu trữ khác.
     *
     * <p>Lỗi thoát ra khỏi adapter phải được bên gọi cho rollback transaction ngoài.
     * Trường hợp trùng mã được SQL xử lý bằng DO NOTHING nên không
     * gây lỗi transaction và có thể thử lại bằng mã khác.
     *
     * <p>ID trả về chưa có nghĩa transaction đã commit.
     *
     * @param reservation aggregate cần lưu, không được null
     * @return ID mới; rỗng chỉ khi SQL bỏ qua xung đột mã
     * @throws ReservationOverlapException nếu vi phạm ràng buộc chống trùng lịch
     * @throws DataAccessException nếu có lỗi lưu trữ khác
     *                             hoặc kết quả trả về bất thường
     */
    @Override
    public OptionalLong insert(Reservation reservation) {
        Objects.requireNonNull(reservation, "reservation must not be null.");

        MapSqlParameterSource parameters = insertParameters(reservation);

        DataAccessException firstDeadlock = null;
        for (int attempt = 1; ; attempt++) {
            try {
                List<Long> ids = Objects.requireNonNull(nestedInsert.execute(status ->
                        jdbcTemplate.queryForList(insertSql, parameters, Long.class)));

                if (ids.isEmpty()) {
                    return OptionalLong.empty();
                }

                if (ids.size() != 1) {
                    throw new IncorrectResultSizeDataAccessException(1, ids.size());
                }

                Long id = ids.getFirst();

                if (id == null) {
                    throw new DataRetrievalFailureException(
                            "Reservation insert returned a null ID."
                    );
                }

                return OptionalLong.of(id);
            } catch (DataAccessException failure) {
                if (isDeadlock(failure)) {
                    if (firstDeadlock == null) {
                        firstDeadlock = failure;
                    }
                    if (attempt < MAX_INSERT_ATTEMPTS) {
                        continue;
                    }
                    throw firstDeadlock;
                }
                if (failure instanceof DataIntegrityViolationException integrityFailure
                        && isReservationOverlap(integrityFailure)) {
                    throw new ReservationOverlapException(failure);
                }

                throw failure;
            }
        }
    }

    /**
     * Cập nhật đúng một khóa nếu trạng thái cũ chưa bị transaction khác thay đổi.
     *
     * <p>Chỉ false khi UPDATE không tác động dòng nào. Lỗi database hoặc
     * số dòng bất thường phải báo lỗi, không giả thành xung đột trạng thái.
     * Không đọc Clock, kiểm cạnh chuyển hoặc tính lại hạn trong adapter.
     *
     * @param code mã khóa lịch
     * @param expectedStatus trạng thái mong đợi trước khi ghi
     * @param newStatus trạng thái mới đã được domain kiểm tra
     * @param changedAt mốc đổi trạng thái từ application
     * @return true nếu cập nhật một dòng; false nếu không có dòng khớp
     * @throws DataAccessException nếu lưu trữ lỗi hoặc số dòng không hợp lệ
     */
    @Override
    public boolean updateStatus(String code, ReservationStatus expectedStatus,
                                ReservationStatus newStatus, Instant changedAt) {
        Objects.requireNonNull(code, "code must not be null.");
        Objects.requireNonNull(expectedStatus, "expectedStatus must not be null.");
        Objects.requireNonNull(newStatus, "newStatus must not be null.");
        Objects.requireNonNull(changedAt, "changedAt must not be null.");
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("code", code, Types.VARCHAR)
                .addValue("expectedStatus", expectedStatus.name(), Types.VARCHAR)
                .addValue("newStatus", newStatus.name(), Types.VARCHAR)
                .addValue("changedAt", toOffsetDateTime(changedAt), Types.TIMESTAMP_WITH_TIMEZONE);
        int affected = jdbcTemplate.update(updateStatusSql, parameters);
        if (affected == 0) {
            return false;
        }
        if (affected == 1) {
            return true;
        }
        throw new IncorrectUpdateSemanticsDataAccessException(
                "Expected to update zero or one reservation row, but got: " + affected
        );
    }

    /**
     * Nhả HELD đến hạn theo mốc application cung cấp; không đọc hoặc tính lại hạn.
     *
     * <p>Điều kiện ngay trong UPDATE bảo vệ trước job khác và xác nhận đồng thời.
     * Không tự mở transaction, không thử lại hoặc đổi lỗi lưu trữ thành xe bận.
     *
     * @param expiredAt mốc dọn và mốc đổi trạng thái, không null
     * @return số dòng cập nhật không âm
     */
    @Override
    public int releaseExpiredHolds(Instant expiredAt) {
        Objects.requireNonNull(expiredAt, "expiredAt must not be null.");
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("expiredAt", toOffsetDateTime(expiredAt), Types.TIMESTAMP_WITH_TIMEZONE);
        int affected = jdbcTemplate.update(releaseExpiredHoldsSql, parameters);
        if (affected < 0) {
            throw new IncorrectUpdateSemanticsDataAccessException("Expected a non-negative expired hold count.");
        }
        return affected;
    }

    /**
     * Chuyển aggregate thành tham số SQL có kiểu rõ ràng.
     *
     * <p>Instant được chuyển thành OffsetDateTime tại UTC cho JDBC.
     * Cận trên null được giữ nguyên để SQL dựng khoảng không chặn trên.
     * Các trường nullable vẫn được khai báo kiểu SQL.
     *
     * @param reservation aggregate đã được kiểm tra không null
     * @return bộ tham số khớp với insert_reservation.sql
     */
    private static MapSqlParameterSource insertParameters(
            Reservation reservation
    ) {
        return new MapSqlParameterSource()
                .addValue("code", reservation.code(), Types.VARCHAR)
                .addValue("vehicleId", reservation.vehicleId(), Types.BIGINT)
                .addValue(
                        "startInclusive",
                        toOffsetDateTime(reservation.period().startInclusive()),
                        Types.TIMESTAMP_WITH_TIMEZONE
                )
                .addValue(
                        "endExclusive",
                        toOffsetDateTime(reservation.period().endExclusive()),
                        Types.TIMESTAMP_WITH_TIMEZONE
                )
                .addValue("kind", reservation.kind().name(), Types.VARCHAR)
                .addValue("status", reservation.status().name(), Types.VARCHAR)
                .addValue("bookingCode", reservation.bookingCode(), Types.VARCHAR)
                .addValue("reason", reservation.reason(), Types.VARCHAR)
                .addValue(
                        "holdExpiresAt",
                        toOffsetDateTime(reservation.holdExpiresAt()),
                        Types.TIMESTAMP_WITH_TIMEZONE
                )
                .addValue(
                        "createdAt",
                        toOffsetDateTime(reservation.createdAt()),
                        Types.TIMESTAMP_WITH_TIMEZONE
                )
                .addValue(
                        "statusChangedAt",
                        toOffsetDateTime(reservation.statusChangedAt()),
                        Types.TIMESTAMP_WITH_TIMEZONE
                );
    }

    /**
     * Biểu diễn một thời điểm tại UTC mà không thay đổi thời điểm thực.
     *
     * @param instant thời điểm cần chuyển, có thể null
     * @return OffsetDateTime tại UTC hoặc null nếu đầu vào null
     */
    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    /**
     * Nhận diện deadlock bằng SQLSTATE PostgreSQL, không dựa vào loại exception Spring hoặc thông báo.
     *
     * @param failure lỗi JDBC sau khi savepoint đã rollback
     * @return true chỉ với PSQLException mang 40P01
     */
    private static boolean isDeadlock(DataAccessException failure) {
        return failure.getMostSpecificCause() instanceof PSQLException postgresFailure
                && DEADLOCK_SQL_STATE.equals(postgresFailure.getSQLState());
    }

    /**
     * Nhận diện đúng lỗi chống trùng lịch bằng thông tin có cấu trúc.
     *
     * <p>Phải khớp SQLSTATE, schema, bảng và tên constraint.
     * Không phân tích chuỗi thông báo lỗi và không coi mọi lỗi toàn vẹn
     * là xe đã bận.
     *
     * @param failure lỗi toàn vẹn dữ liệu do JDBC báo
     * @return true chỉ khi xác định đúng reservation_no_overlap
     */
    private static boolean isReservationOverlap(
            DataIntegrityViolationException failure
    ) {
        if (!(failure.getMostSpecificCause()
                instanceof PSQLException postgresFailure)) {
            return false;
        }

        if (!EXCLUSION_VIOLATION_SQL_STATE.equals(
                postgresFailure.getSQLState()
        )) {
            return false;
        }

        ServerErrorMessage details = postgresFailure.getServerErrorMessage();

        return details != null
                && "availability".equals(details.getSchema())
                && "reservation".equals(details.getTable())
                && OVERLAP_CONSTRAINT.equals(details.getConstraint());
    }
}
