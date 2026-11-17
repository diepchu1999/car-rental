package com.carrental.booking.adapter.out.persistence;

import com.carrental.booking.application.port.out.PendingBookingExistsException;
import com.carrental.booking.application.port.out.WriteBookingPort;
import com.carrental.booking.domain.Booking;
import com.carrental.booking.domain.BookingStatus;
import com.carrental.shared.sql.SqlLoader;
import java.sql.Statement;
import java.sql.Types;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.IncorrectUpdateSemanticsDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static com.carrental.booking.adapter.out.persistence.BookingJdbcValues.*;

/** Ghi toàn bộ đơn trong transaction của use case; không ghi bảng availability (BR-102/122/131). */
@Repository
class BookingWriteAdapter implements WriteBookingPort {
    private final NamedParameterJdbcTemplate jdbc;
    private final String insert;
    private final String insertLine;
    private final String insertCreated;
    private final String cancelByCustomer;
    private final String cancelExpired;

    /** Tải SQL một lần; không tự commit header trước khi lưu dòng giá/lịch sử. */
    BookingWriteAdapter(NamedParameterJdbcTemplate jdbc, SqlLoader loader) {
        this.jdbc = jdbc;
        insert = loader.load(BookingSqlPaths.INSERT);
        insertLine = loader.load(BookingSqlPaths.INSERT_LINE);
        insertCreated = loader.load(BookingSqlPaths.INSERT_CREATED);
        cancelByCustomer = loader.load(BookingSqlPaths.CANCEL_BY_CUSTOMER);
        cancelExpired = loader.load(BookingSqlPaths.CANCEL_EXPIRED_PENDING);
    }

    /**
     * False chỉ khi trùng mã. Unique đơn chờ được dịch riêng; lỗi khác giữ nguyên để rollback.
     * MANDATORY ngăn gọi adapter ngoài transaction rồi vô tình commit từng phần.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean insert(Booking booking) {
        if (booking.status() != BookingStatus.PENDING_PAYMENT || !booking.statusChangedAt().equals(booking.createdAt())) {
            throw new IllegalArgumentException("Only a newly created booking can be inserted.");
        }
        java.util.List<Long> ids;
        try {
            ids = jdbc.queryForList(insert, parameters(booking), Long.class);
        } catch (DataIntegrityViolationException failure) {
            if (isPendingCustomerViolation(failure)) throw new PendingBookingExistsException(failure);
            throw failure;
        }
        if (ids.isEmpty()) return false;
        if (ids.size() != 1) throw new IncorrectUpdateSemanticsDataAccessException("Expected one inserted booking.");
        var lines = new MapSqlParameterSource[booking.price().lines().size()];
        for (int index = 0; index < lines.length; index++) {
            var line = booking.price().lines().get(index);
            lines[index] = new MapSqlParameterSource().addValue("bookingId", ids.getFirst(), Types.BIGINT)
                    .addValue("lineNumber", index + 1, Types.INTEGER).addValue("type", line.type(), Types.VARCHAR)
                    .addValue("dayType", line.dayType(), Types.VARCHAR)
                    .addValue("startAt", timestamp(line.startAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                    .addValue("hours", line.hours(), Types.INTEGER).addValue("dailyRate", line.dailyRate(), Types.BIGINT)
                    .addValue("uplift", line.uplift(), Types.NUMERIC).addValue("amount", line.amount(), Types.BIGINT);
        }
        for (int count : jdbc.batchUpdate(insertLine, lines)) {
            if (count != 1 && count != Statement.SUCCESS_NO_INFO) {
                throw new IncorrectUpdateSemanticsDataAccessException("Expected one inserted booking price line.");
            }
        }
        if (jdbc.update(insertCreated, new MapSqlParameterSource().addValue("bookingId", ids.getFirst(), Types.BIGINT)
                .addValue("actorAccountCode", booking.customerAccountCode(), Types.VARCHAR)
                .addValue("occurredAt", timestamp(booking.createdAt()), Types.TIMESTAMP_WITH_TIMEZONE)) != 1) {
            throw new IncorrectUpdateSemanticsDataAccessException("Expected one created booking history event.");
        }
        return true;
    }

    /** Cùng transaction ngoài với availability; lịch sử dùng tác nhân hệ thống, không giả tài khoản. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean cancelExpiredPending(Booking cancelled) {
        if (cancelled.status() != BookingStatus.CANCELLED || cancelled.statusChangedAt().isBefore(cancelled.holdExpiresAt())) {
            throw new IllegalArgumentException("Expected a cancelled, expired booking.");
        }
        int affected = jdbc.update(cancelExpired, new MapSqlParameterSource()
                .addValue("code", cancelled.code(), Types.VARCHAR)
                .addValue("reservationCode", cancelled.reservationCode(), Types.VARCHAR)
                .addValue("holdExpiresAt", timestamp(cancelled.holdExpiresAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("changedAt", timestamp(cancelled.statusChangedAt()), Types.TIMESTAMP_WITH_TIMEZONE));
        if (affected == 0) return false;
        if (affected == 1) return true;
        throw new IncorrectUpdateSemanticsDataAccessException("Expected zero or one expired booking history event.");
    }

    /** Chỉ cập nhật trạng thái và mốc huỷ; SQL dùng chủ lưu ở CSDL làm tác nhân lịch sử. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean cancelPendingByCustomer(Booking cancelled) {
        if (cancelled.status() != BookingStatus.CANCELLED) {
            throw new IllegalArgumentException("Expected a cancelled booking.");
        }
        int affected = jdbc.update(cancelByCustomer, new MapSqlParameterSource()
                .addValue("code", cancelled.code(), Types.VARCHAR)
                .addValue("customerAccountCode", cancelled.customerAccountCode(), Types.VARCHAR)
                .addValue("changedAt", timestamp(cancelled.statusChangedAt()), Types.TIMESTAMP_WITH_TIMEZONE));
        if (affected == 0) return false;
        if (affected == 1) return true;
        throw new IncorrectUpdateSemanticsDataAccessException("Expected zero or one cancelled booking history event.");
    }

    /** Chỉ nhận đúng SQLSTATE, schema, bảng và unique BR-131; không bắt mọi lỗi 23505. */
    private static boolean isPendingCustomerViolation(DataIntegrityViolationException failure) {
        if (!(failure.getMostSpecificCause() instanceof PSQLException cause) || !"23505".equals(cause.getSQLState())) return false;
        var error = cause.getServerErrorMessage();
        return error != null && "booking".equals(error.getSchema()) && "booking".equals(error.getTable())
                && "uq_booking_pending_customer".equals(error.getConstraint());
    }

    /** Map snapshot và kiểu JDBC tường minh, không đọc cấu hình hoặc làm tròn lại tiền. */
    private static MapSqlParameterSource parameters(Booking booking) {
        var price = booking.price();
        var amounts = price.amounts();
        var terms = booking.rentalTerms();
        return new MapSqlParameterSource()
                .addValue("code", booking.code(), Types.VARCHAR)
                .addValue("customer_account_code", booking.customerAccountCode(), Types.VARCHAR)
                .addValue("quote_code", booking.quoteCode(), Types.VARCHAR)
                .addValue("vehicle_code", booking.vehicleCode(), Types.VARCHAR)
                .addValue("branch_code", booking.branchCode(), Types.VARCHAR)
                .addValue("reservation_code", booking.reservationCode(), Types.VARCHAR)
                .addValue("start_at", timestamp(booking.startAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("end_at", timestamp(booking.endAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("rental_type", booking.rentalType().name(), Types.VARCHAR)
                .addValue("drive_mode", booking.driveMode().name(), Types.VARCHAR)
                .addValue("pickup_method", booking.pickupMethod().name(), Types.VARCHAR)
                .addValue("daily_rate", price.dailyRate(), Types.BIGINT)
                .addValue("insurance_calculation", price.insuranceCalculation(), Types.VARCHAR)
                .addValue("insurance_value", price.insuranceValue(), Types.NUMERIC)
                .addValue("insurance_billing_days", price.insuranceBillingDays(), Types.INTEGER)
                .addValue("rental_amount", amounts.rentalAmount(), Types.BIGINT)
                .addValue("insurance_fee", amounts.insuranceFee(), Types.BIGINT)
                .addValue("total_amount", amounts.totalAmount(), Types.BIGINT)
                .addValue("deposit_ratio", amounts.depositRatio(), Types.NUMERIC)
                .addValue("deposit", amounts.deposit(), Types.BIGINT)
                .addValue("balance", amounts.balance(), Types.BIGINT)
                .addValue("turnaround_buffer_seconds", seconds(terms.turnaroundBuffer()), Types.NUMERIC)
                .addValue("minimum_duration_seconds", terms.minimumDuration().map(BookingJdbcValues::seconds).orElse(null), Types.NUMERIC)
                .addValue("branch_opens_at", terms.branchOpensAt(), Types.TIME)
                .addValue("branch_closes_at", terms.branchClosesAt(), Types.TIME)
                .addValue("minimum_advance_seconds", seconds(terms.minimumAdvance()), Types.NUMERIC)
                .addValue("maximum_advance_months", Math.toIntExact(terms.maximumAdvance().toTotalMonths()), Types.INTEGER)
                .addValue("hold_expires_at", timestamp(booking.holdExpiresAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("status", booking.status().name(), Types.VARCHAR)
                .addValue("created_at", timestamp(booking.createdAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("status_changed_at", timestamp(booking.statusChangedAt()), Types.TIMESTAMP_WITH_TIMEZONE);
    }
}
