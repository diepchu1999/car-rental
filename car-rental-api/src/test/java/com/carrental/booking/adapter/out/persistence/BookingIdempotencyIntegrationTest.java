package com.carrental.booking.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import com.carrental.booking.application.port.out.*;
import com.carrental.booking.application.view.BookingIdempotencyClaim;
import com.carrental.booking.domain.BookingIdempotency;
import com.carrental.booking.domain.Booking;
import com.carrental.shared.sql.SqlLoader;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.IncorrectUpdateSemanticsDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.carrental.booking.domain.BookingTestFixtures.CREATED;
import static com.carrental.booking.adapter.out.persistence.BookingPersistenceFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Key thật trong PostgreSQL: phạm vi, biên 24 giờ, FK hoãn và rollback, không mock cổng ghi. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
class BookingIdempotencyIntegrationTest {
    @Autowired private WriteBookingIdempotencyPort keys;
    @Autowired private WriteBookingPort bookings;
    @Autowired private ReadBookingPort reads;
    @Autowired private NamedParameterJdbcTemplate jdbc;
    @Autowired private SqlLoader sql;
    @Autowired private PlatformTransactionManager transactions;

    /** Ba request cùng key không kéo dài TTL, không đổi nội dung và chỉ có một kết quả. */
    @Test
    void replaysExistingClaimWithoutExtendingExpiry() {
        var original = claim("IDEM01", "TK-IDEM01", "repeat-key", CREATED);
        commit(original, "IDEM01");
        for (int seconds : new int[] {1, 3600, 86399}) {
            var candidate = claim("IDEM02", "TK-IDEM01", "repeat-key", CREATED.plusSeconds(seconds));
            transaction(() -> {
                var result = keys.claim(candidate);
                // Kiểm trước commit để FK hoãn không che lỗi ghi đè key còn hạn trong phép thử đảo.
                assertFalse(result.newlyClaimed());
                assertEquals(original, result.record());
                return null;
            });
        }
        assertTrue(reads.loadOwned("DT-2611-IDEM02", "TK-IDEM01").isEmpty());
        assertEquals(1, countKeys("TK-IDEM01", "repeat-key"));
    }

    /** Key còn hạn, quote khác phải giữ quote gốc để use case từ chối IDEMPOTENCY_KEY_REUSED. */
    @Test
    void differentPayloadCannotOverwriteActiveKey() {
        var original = claim("IDEM03", "TK-IDEM03", "same-key", CREATED);
        commit(original, "IDEM03");
        var result = transaction(() -> keys.claim(claim("IDEM04", "TK-IDEM03", "same-key", CREATED.plusSeconds(30))));
        assertFalse(result.newlyClaimed());
        assertEquals(original.quoteCode(), result.record().quoteCode());
        assertEquals(original.bookingCode(), result.record().bookingCode());
    }

    /** Hai khách độc lập dùng được cùng chuỗi key; không lộ mã đơn của khách kia. */
    @Test
    void scopesKeyToCustomer() {
        var first = claim("IDEM05", "TK-IDEM05", "shared-key", CREATED);
        var second = claim("IDEM06", "TK-IDEM06", "shared-key", CREATED);
        commit(first, "IDEM05");
        commit(second, "IDEM06");
        assertEquals(first, transaction(() -> keys.claim(first)).record());
        assertEquals(second, transaction(() -> keys.claim(second)).record());
    }

    /** Trước biên một micro giây còn phát lại; đúng 24 giờ được chiếm mới, đơn cũ vẫn giữ nguyên. */
    @Test
    void replacesAtExactTwentyFourHourBoundary() {
        var original = claim("IDEM07", "TK-IDEM07", "boundary", CREATED);
        commit(original, "IDEM07");
        cancelForFixture(original.bookingCode(), CREATED.plusSeconds(600));
        var before = transaction(() -> keys.claim(claim("IDEM08", "TK-IDEM07", "boundary", original.expiresAt().minusNanos(1000))));
        assertFalse(before.newlyClaimed());
        assertEquals(original, before.record());
        var next = claim("IDEM08", "TK-IDEM07", "boundary", original.expiresAt());
        commit(next, "IDEM08");
        assertEquals(next, transaction(() -> keys.claim(next)).record());
        assertEquals(1, countKeys("TK-IDEM07", "boundary"));
        assertTrue(reads.loadOwned(original.bookingCode(), original.customerAccountCode()).isPresent());
    }

    /** Key quá 24 giờ không phát lại, kể cả không có job dọn key. */
    @Test
    void replacesAlreadyExpiredKey() {
        var original = claim("IDEM09", "TK-IDEM09", "expired", CREATED);
        commit(original, "IDEM09");
        cancelForFixture(original.bookingCode(), CREATED.plusSeconds(600));
        var next = claim("IDEM10", "TK-IDEM09", "expired", CREATED.plusSeconds(172800));
        commit(next, "IDEM10");
        assertEquals(next, transaction(() -> keys.claim(next)).record());
    }

    /** Chưa ghi đơn thì commit phải bị FK hoãn chặn; claim không được lưu lại. */
    @Test
    void unfinishedClaimCannotCommit() {
        var value = claim("IDEM11", "TK-IDEM11", "unfinished", CREATED);
        var failure = assertThrows(RuntimeException.class, () -> transaction(() -> keys.claim(value)));
        Throwable root = failure;
        while (root.getCause() != null) root = root.getCause();
        var cause = assertInstanceOf(PSQLException.class, root);
        assertEquals("23503", cause.getSQLState());
        assertEquals("fk_booking_idempotency_booking", cause.getServerErrorMessage().getConstraint());
        assertEquals(0, countKeys("TK-IDEM11", "unfinished"));
        commit(value, "IDEM11");
    }

    /** Lỗi phía sau ghi đơn rollback cả key, header, dòng và lịch sử; cùng key dùng lại được. */
    @Test
    void rollbackDoesNotConsumeKey() {
        var value = claim("IDEM12", "TK-IDEM12", "rollback", CREATED);
        var failure = new IllegalStateException("Simulated outer use case failure.");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> transaction(() -> {
            assertTrue(keys.claim(value).newlyClaimed());
            assertTrue(bookings.insert(booking("IDEM12", value.customerAccountCode(), CREATED)));
            throw failure;
        })));
        assertEquals(0, countKeys("TK-IDEM12", "rollback"));
        assertTrue(reads.loadOwned(value.bookingCode(), value.customerAccountCode()).isEmpty());
        commit(value, "IDEM12");
    }

    /** Sau khi trùng mã DT, chỉ con trỏ đổi; key, quote và hạn vẫn của request ban đầu. */
    @Test
    void canReplaceCollidingBookingCodeBeforeCommit() {
        var value = claim("IDEM13", "TK-IDEM13", "code-retry", CREATED);
        transaction(() -> {
            assertTrue(keys.claim(value).newlyClaimed());
            keys.replaceClaimedBookingCode(value, "DT-2611-IDEM14");
            var template = booking("IDEM14", value.customerAccountCode(), CREATED);
            assertTrue(bookings.insert(Booking.create(template.code(), template.customerAccountCode(), value.quoteCode(),
                    template.vehicleCode(), template.branchCode(), template.reservationCode(), template.startAt(), template.endAt(),
                    template.rentalType(), template.driveMode(), template.pickupMethod(), template.price(), template.rentalTerms(),
                    template.holdExpiresAt(), template.createdAt())));
            return null;
        });
        var result = transaction(() -> keys.claim(value));
        assertFalse(result.newlyClaimed());
        assertEquals("DT-2611-IDEM14", result.record().bookingCode());
        assertEquals(value.quoteCode(), result.record().quoteCode());
        assertEquals(value.expiresAt(), result.record().expiresAt());
    }

    /** Không được cập nhật con trỏ khi expected không còn khớp claim đang bị khoá. */
    @Test
    void rejectsStaleClaimReplacement() {
        var value = claim("IDEM15", "TK-IDEM15", "stale", CREATED);
        assertThrows(IncorrectUpdateSemanticsDataAccessException.class, () -> transaction(() -> {
            keys.claim(value);
            keys.replaceClaimedBookingCode(claim("IDEM16", "TK-IDEM15", "stale", CREATED), "DT-2611-IDEM17");
            return null;
        }));
        assertEquals(0, countKeys("TK-IDEM15", "stale"));
    }

    /** Không có transaction thì không được chiếm key rồi tự commit trước khi tạo đơn. */
    @Test
    void requiresOuterTransaction() {
        assertThrows(IllegalTransactionStateException.class, () -> keys.claim(claim("IDEM18", "TK-IDEM18", "outside", CREATED)));
        assertEquals(0, countKeys("TK-IDEM18", "outside"));
    }

    /** Thay key hết hạn nhưng ghi đơn mới lỗi thì bản key cũ phải được khôi phục cùng transaction. */
    @Test
    void failedReplacementRestoresExpiredRecord() {
        var original = claim("IDEM19", "TK-IDEM19", "replace-failure", CREATED);
        commit(original, "IDEM19");
        var next = claim("IDEM20", "TK-IDEM19", "replace-failure", original.expiresAt());
        assertThrows(PendingBookingExistsException.class, () -> transaction(() -> {
            assertTrue(keys.claim(next).newlyClaimed());
            // Đơn cũ cố ý vẫn chờ: BR-131 làm INSERT mới thất bại, UPDATE key phải rollback theo.
            bookings.insert(booking("IDEM20", "TK-IDEM19", next.createdAt()));
            return null;
        }));
        // Mốc trước hạn dùng riêng để đọc bản cũ mà không tái chiếm; không phải Clock production đi ngược.
        assertEquals(original, transaction(() -> keys.claim(original)).record());
        assertTrue(reads.loadOwned(next.bookingCode(), next.customerAccountCode()).isEmpty());
    }

    /** Chiếm trước, ghi đơn sau; FK được kiểm lúc transaction ngoài commit. */
    private void commit(BookingIdempotency value, String suffix) {
        transaction(() -> {
            var result = keys.claim(value);
            assertTrue(result.newlyClaimed());
            assertEquals(value, result.record());
            assertTrue(bookings.insert(booking(suffix, value.customerAccountCode(), value.createdAt())));
            return null;
        });
    }

    /** Chỉ dựng trạng thái test để giải phóng unique BR-131; chưa hiện thực use case huỷ của Phần 4. */
    private void cancelForFixture(String code, Instant at) {
        transaction(() -> jdbc.update(sql.load("sql/booking/cancel_booking_for_idempotency_test.sql"),
                Map.of("code", code, "changedAt", BookingJdbcValues.timestamp(at))));
    }

    /** Đọc độc lập sau khi transaction đã đóng, không suy từ kết quả claim. */
    private int countKeys(String owner, String key) {
        return jdbc.queryForObject(sql.load("sql/booking/count_idempotency_for_adapter_test.sql"),
                Map.of("customerAccountCode", owner, "key", key), Integer.class);
    }

    /** Transaction thật thuộc về bên gọi; không bao quanh toàn test. */
    private <T> T transaction(Supplier<T> action) {
        return new TransactionTemplate(transactions).execute(status -> action.get());
    }
}
