package com.carrental.booking.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import com.carrental.booking.application.port.out.*;
import com.carrental.booking.domain.*;
import com.carrental.shared.sql.SqlLoader;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;
import java.time.Period;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.carrental.booking.domain.BookingTestFixtures.*;
import static com.carrental.booking.adapter.out.persistence.BookingPersistenceFixtures.booking;
import static org.junit.jupiter.api.Assertions.*;

/** Kiểm adapter thật, commit thật và đọc lại độc lập; không dùng transaction tự rollback bao quanh test. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
class BookingPersistenceIntegrationTest {
    @Autowired private WriteBookingPort writes;
    @Autowired private ReadBookingPort reads;
    @Autowired private NamedParameterJdbcTemplate jdbc;
    @Autowired private SqlLoader sql;
    @Autowired private PlatformTransactionManager transactions;

    /** Đóng băng hai dòng có thứ tự, tiền và Duration nano chính xác; sai chủ giống không tồn tại. */
    @Test
    void roundTripsFrozenValuesAndRestrictsOwner() {
        var first = new BookingPriceLine("DAY_BLOCK", "HOLIDAY", START, 24, 1_000_000,
                new BigDecimal("0.15"), 1_150_000);
        var second = new BookingPriceLine("DAY_BLOCK", "WEEKDAY", END, 24, 1_000_000, BigDecimal.ZERO, 1_000_000);
        var price = new BookingPriceSnapshot(1_000_000, List.of(first, second), "PERCENT_OF_RENT",
                new BigDecimal("0.125"), 2, BookingAmounts.calculate(2_150_000, 269_000, new BigDecimal("0.30")));
        var terms = new BookingRentalTerms(Duration.ofSeconds(7200, 123456789), Optional.of(Duration.ofHours(4)),
                LocalTime.of(7, 0, 0, 123456000), LocalTime.of(22, 0), Duration.ofSeconds(3600, 1), Period.ofMonths(6));
        var template = booking("PERS01", "TK-PERS01", CREATED);
        var expected = Booking.create(template.code(), template.customerAccountCode(), template.quoteCode(), template.vehicleCode(),
                template.branchCode(), template.reservationCode(), START, END.plusSeconds(86400), template.rentalType(),
                template.driveMode(), template.pickupMethod(), price, terms, template.holdExpiresAt(), CREATED);
        inTransaction(() -> assertTrue(writes.insert(expected)));
        assertEquals(expected, reads.loadOwned(expected.code(), expected.customerAccountCode()).orElseThrow());
        assertTrue(reads.loadOwned(expected.code(), "TK-OTHER1").isEmpty());
        assertTrue(reads.loadOwned("DT-2611-ABSENT", expected.customerAccountCode()).isEmpty());
        var history = jdbc.queryForMap(sql.load("sql/booking/read_created_history_for_adapter_test.sql"), Map.of("code", expected.code()));
        assertNull(history.get("from_status"));
        assertEquals("PENDING_PAYMENT", history.get("to_status"));
        assertEquals("TK-PERS01", history.get("actor_account_code"));
        assertEquals("CREATED", history.get("reason"));
    }

    /** Trùng mã chỉ trả false; không chèn thêm dòng/lịch sử và không làm hỏng transaction bên gọi. */
    @Test
    void duplicateCodeDoesNotDuplicateChildren() {
        var value = booking("PERS02", "TK-PERS02", CREATED);
        inTransaction(() -> assertTrue(writes.insert(value)));
        inTransaction(() -> {
            assertFalse(writes.insert(value));
            assertEquals(value, reads.loadOwned(value.code(), value.customerAccountCode()).orElseThrow());
        });
        assertCounts(value.code(), 1, 1, 1);
    }

    /** Unique đơn chờ dịch đúng hợp đồng cổng; đơn thua không để lại header/dòng/lịch sử. */
    @Test
    void translatesOnlyPendingCustomerConflict() {
        var first = booking("PERS03", "TK-PERS03", CREATED);
        var second = booking("PERS04", "TK-PERS03", CREATED);
        inTransaction(() -> assertTrue(writes.insert(first)));
        var failure = assertThrows(PendingBookingExistsException.class, () -> inTransaction(() -> writes.insert(second)));
        var dataFailure = assertInstanceOf(DataIntegrityViolationException.class, failure.getCause());
        var postgres = assertInstanceOf(PSQLException.class, dataFailure.getMostSpecificCause());
        assertEquals("23505", postgres.getSQLState());
        assertEquals("uq_booking_pending_customer", postgres.getServerErrorMessage().getConstraint());
        assertCounts(second.code(), 0, 0, 0);
    }

    /** Trùng mã reservation cũng là 23505 nhưng không được giả thành khách đã có đơn chờ. */
    @Test
    void unrelatedUniqueViolationKeepsOriginalFailure() {
        var first = booking("PERS05", "TK-PERS05", CREATED);
        var template = booking("PERS06", "TK-PERS06", CREATED);
        var second = new Booking(template.code(), template.customerAccountCode(), template.quoteCode(), template.vehicleCode(),
                template.branchCode(), first.reservationCode(), template.startAt(), template.endAt(), template.rentalType(),
                template.driveMode(), template.pickupMethod(), template.price(), template.rentalTerms(), template.holdExpiresAt(),
                template.status(), template.createdAt(), template.statusChangedAt());
        inTransaction(() -> writes.insert(first));
        var failure = assertThrows(DataIntegrityViolationException.class, () -> inTransaction(() -> writes.insert(second)));
        var cause = assertInstanceOf(PSQLException.class, failure.getMostSpecificCause());
        assertEquals("23505", cause.getSQLState());
        assertEquals("uq_booking_reservation_code", cause.getServerErrorMessage().getConstraint());
        assertCounts(second.code(), 0, 0, 0);
    }

    /** Lịch sử ném lỗi sau khi đã chèn header và dòng; cả ba phải rollback, trigger thử cũng rollback. */
    @Test
    void historyFailureRollsBackEntireAggregate() {
        var value = booking("PERS07", "TK-PERS07", CREATED);
        var failure = assertThrows(DataIntegrityViolationException.class, () -> inTransaction(() -> {
            jdbc.getJdbcTemplate().execute(sql.load("sql/booking/install_history_failure_for_adapter_test.sql"));
            writes.insert(value);
        }));
        var cause = assertInstanceOf(PSQLException.class, failure.getMostSpecificCause());
        assertEquals("23514", cause.getSQLState());
        assertEquals("test_booking_history_failure", cause.getServerErrorMessage().getConstraint());
        assertCounts(value.code(), 0, 0, 0);
        inTransaction(() -> assertTrue(writes.insert(value)));
        assertCounts(value.code(), 1, 1, 1);
    }

    /** Gọi cổng ghi không có transaction phải bị chặn trước khi một header có thể tự commit. */
    @Test
    void requiresOuterTransaction() {
        var value = booking("PERS08", "TK-PERS08", CREATED);
        assertThrows(IllegalTransactionStateException.class, () -> writes.insert(value));
        assertCounts(value.code(), 0, 0, 0);
    }

    /** Một ranh giới transaction thật cho mỗi thao tác; assertion ném lỗi cũng rollback. */
    private void inTransaction(Runnable action) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> action.run());
    }

    /** Đọc số hàng độc lập sau commit/rollback, không suy từ kết quả trả về của adapter. */
    private void assertCounts(String code, long headers, long lines, long history) {
        var values = jdbc.queryForMap(sql.load("sql/booking/count_booking_rows_for_adapter_test.sql"), Map.of("code", code));
        assertEquals(headers, ((Number) values.get("headers")).longValue());
        assertEquals(lines, ((Number) values.get("lines")).longValue());
        assertEquals(history, ((Number) values.get("history")).longValue());
    }
}
