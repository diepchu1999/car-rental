package com.carrental.availability.adapter.out.persistence;

import com.carrental.availability.domain.Reservation;
import com.carrental.availability.domain.ReservationKind;
import com.carrental.availability.domain.ReservationPeriod;
import com.carrental.availability.domain.ReservationStatus;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Khôi phục khóa lịch trực tiếp từ JDBC theo BR-103, BR-015 và ADR-0005.
 *
 * <p>Không đọc Clock hoặc cấu hình, không tính lại hạn và không cộng đệm.
 * SQL cung cấp từng cận của range; hình dạng [) được V004 bảo vệ tại CSDL.
 */
final class ReservationRowMappers {

    /** Bộ ánh xạ aggregate, không thông qua view hoặc DTO trung gian. */
    static final RowMapper<Reservation> AGGREGATE = ReservationRowMappers::mapAggregate;

    /** Ngăn tạo đối tượng vì lớp chỉ cung cấp bộ ánh xạ dùng chung. */
    private ReservationRowMappers() {
    }

    /**
     * Đọc từng cận và khôi phục đúng trạng thái, thời hạn đã lưu.
     *
     * <p>upper_inf quyết định khoảng không chặn trên. Với khoảng hữu hạn,
     * cận trên phải tồn tại; không diễn giải cột thiếu thành khóa vô hạn.
     * Dữ liệu sai phải gây lỗi, không đổi thành kết quả không tìm thấy.
     *
     * @param resultSet dòng JDBC hiện tại do Spring quản lý
     * @param rowNumber chỉ số dòng do Spring cung cấp
     * @return aggregate khôi phục từ dữ liệu lưu trữ
     * @throws SQLException nếu đọc cột JDBC thất bại
     */
    private static Reservation mapAggregate(ResultSet resultSet, int rowNumber) throws SQLException {
        Instant start = readInstant(resultSet, "starts_at");
        ReservationPeriod period = resultSet.getBoolean("end_unbounded")
                ? ReservationPeriod.unboundedFrom(start)
                : ReservationPeriod.finite(start, readInstant(resultSet, "ends_at"));

        return Reservation.restore(
                resultSet.getString("code"), resultSet.getLong("vehicle_id"), period,
                ReservationKind.valueOf(resultSet.getString("kind")),
                ReservationStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("booking_code"), resultSet.getString("reason"),
                readInstant(resultSet, "hold_expires_at"), readInstant(resultSet, "created_at"),
                readInstant(resultSet, "status_changed_at")
        );
    }

    /**
     * Chuyển timestamptz sang Instant, giữ nguyên thời điểm bất kể offset của JDBC.
     *
     * @param resultSet dòng hiện tại
     * @param column tên cột cố định của truy vấn
     * @return thời điểm hoặc null nếu cột không có giá trị
     * @throws SQLException nếu đọc cột thất bại
     */
    private static Instant readInstant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
