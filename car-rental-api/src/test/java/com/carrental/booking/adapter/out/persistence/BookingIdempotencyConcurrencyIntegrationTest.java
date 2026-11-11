package com.carrental.booking.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import com.carrental.booking.application.port.out.*;
import com.carrental.booking.application.view.BookingIdempotencyClaim;
import com.carrental.booking.domain.BookingIdempotency;
import com.carrental.shared.sql.SqlLoader;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.carrental.booking.domain.BookingTestFixtures.CREATED;
import static com.carrental.booking.adapter.out.persistence.BookingPersistenceFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Hai connection PostgreSQL thật kiểm tranh chấp key và unique đơn chờ; chưa thay cuộc đua giữ xe qua use case. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
class BookingIdempotencyConcurrencyIntegrationTest {
    @Autowired private WriteBookingIdempotencyPort keys;
    @Autowired private WriteBookingPort bookings;
    @Autowired private ReadBookingPort reads;
    @Autowired private NamedParameterJdbcTemplate jdbc;
    @Autowired private SqlLoader sql;
    @Autowired private PlatformTransactionManager transactions;

    /** Cùng khách/key chỉ một request chiếm mới; bên còn lại đọc kết quả đã commit của bên thắng. */
    @Test
    void sameKeyCreatesOnlyOneBooking() throws Exception {
        assertSingleClaim(race("KRC001", "KRC002", "TK-KRC001", "same", "same", CREATED, false), "TK-KRC001", "same");
    }

    /** Hai request thay cùng key hết hạn tại đúng biên vẫn chỉ có một kết quả mới. */
    @Test
    void expiredKeyCanBeReplacedByOnlyOneTransaction() throws Exception {
        var original = claim("KRC003", "TK-KRC003", "expired-race", CREATED);
        transaction().executeWithoutResult(status -> {
            assertTrue(keys.claim(original).newlyClaimed());
            assertTrue(bookings.insert(booking("KRC003", "TK-KRC003", CREATED)));
            jdbc.update(sql.load("sql/booking/cancel_booking_for_idempotency_test.sql"),
                    Map.of("code", original.bookingCode(), "changedAt", BookingJdbcValues.timestamp(CREATED.plusSeconds(10))));
        });
        assertSingleClaim(race("KRC004", "KRC005", "TK-KRC003", "expired-race", "expired-race", original.expiresAt(), false),
                "TK-KRC003", "expired-race");
        assertTrue(reads.loadOwned(original.bookingCode(), "TK-KRC003").isPresent());
    }

    /** Hai key khác nhau đều chiếm được nhưng BR-131 chỉ cho một đơn; key của bên thua phải rollback. */
    @Test
    void differentKeysCannotBypassOnePendingBookingPerCustomer() throws Exception {
        var outcomes = race("KRC006", "KRC007", "TK-KRC006", "first", "second", CREATED, true);
        assertEquals(1, outcomes.stream().filter(outcome -> outcome.failure() == null).count());
        var failed = outcomes.stream().filter(outcome -> outcome.failure() != null).findFirst().orElseThrow();
        var cause = assertInstanceOf(DataIntegrityViolationException.class, failed.failure().getCause());
        var postgres = assertInstanceOf(PSQLException.class, cause.getMostSpecificCause());
        assertEquals("23505", postgres.getSQLState());
        assertEquals("uq_booking_pending_customer", postgres.getServerErrorMessage().getConstraint());
        assertEquals(0, countKeys("TK-KRC006", failed.request().key()));
        assertTrue(reads.loadOwned(failed.request().bookingCode(), "TK-KRC006").isEmpty());
        assertEquals(1L, pending("TK-KRC006"));
    }

    /** Chứng minh request sau thực sự đang chờ khoá; transaction đầu rollback thì request sau chiếm được key. */
    @Test
    void waitingRequestCanClaimAfterFirstTransactionRollsBack() throws Exception {
        var claimed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> transaction().execute(status -> {
                assertTrue(keys.claim(claim("KRC008", "TK-KRC008", "rollback-race", CREATED)).newlyClaimed());
                claimed.countDown();
                await(release);
                status.setRollbackOnly();
                return true;
            }));
            assertTrue(claimed.await(10, TimeUnit.SECONDS));
            var second = pool.submit(() -> transaction().execute(status -> {
                var result = keys.claim(claim("KRC009", "TK-KRC008", "rollback-race", CREATED));
                assertTrue(result.newlyClaimed());
                assertTrue(bookings.insert(booking("KRC009", "TK-KRC008", CREATED)));
                return result;
            }));
            waitForBlockedKey();
            release.countDown();
            assertEquals(Boolean.TRUE, first.get(20, TimeUnit.SECONDS));
            assertEquals("DT-2611-KRC009", second.get(20, TimeUnit.SECONDS).record().bookingCode());
            assertTrue(reads.loadOwned("DT-2611-KRC008", "TK-KRC008").isEmpty());
            assertTrue(reads.loadOwned("DT-2611-KRC009", "TK-KRC008").isPresent());
            assertEquals(1, countKeys("TK-KRC008", "rollback-race"));
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    /** Barrier đặt trước claim; riêng hai key dùng thêm barrier trước INSERT đơn để ép tranh chấp BR-131. */
    private List<Outcome> race(String firstSuffix, String secondSuffix, String owner, String firstKey,
            String secondKey, Instant at, boolean synchronizeBookings) throws Exception {
        var start = new CyclicBarrier(2);
        var write = synchronizeBookings ? new CyclicBarrier(2) : null;
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> run(firstSuffix, owner, firstKey, at, start, write));
            var second = pool.submit(() -> run(secondSuffix, owner, secondKey, at, start, write));
            return List.of(first.get(25, TimeUnit.SECONDS), second.get(25, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    /** Không nuốt lỗi bất ngờ: chỉ unique BR-131 là kết quả thua hợp lệ ở cuộc đua hai key. */
    private Outcome run(String suffix, String owner, String key, Instant at, CyclicBarrier start, CyclicBarrier write) {
        var request = claim(suffix, owner, key, at);
        try {
            var result = transaction().execute(status -> {
                await(start);
                var value = keys.claim(request);
                if (value.newlyClaimed()) {
                    if (write != null) await(write);
                    assertTrue(bookings.insert(booking(suffix, owner, at)));
                }
                return value;
            });
            return new Outcome(request, result, null);
        } catch (PendingBookingExistsException failure) {
            return new Outcome(request, null, failure);
        }
    }

    /** Đọc lại độc lập: chỉ một key, một đơn chờ; hai request nhận đúng cùng bản ghi đã chốt. */
    private void assertSingleClaim(List<Outcome> outcomes, String owner, String key) {
        assertTrue(outcomes.stream().allMatch(outcome -> outcome.failure() == null));
        assertEquals(1, outcomes.stream().filter(outcome -> outcome.result().newlyClaimed()).count());
        assertEquals(outcomes.getFirst().result().record(), outcomes.getLast().result().record());
        var winner = outcomes.stream().filter(outcome -> outcome.result().newlyClaimed()).findFirst().orElseThrow();
        var loser = outcomes.stream().filter(outcome -> !outcome.result().newlyClaimed()).findFirst().orElseThrow();
        assertEquals(winner.request(), winner.result().record());
        assertTrue(reads.loadOwned(winner.request().bookingCode(), owner).isPresent());
        assertTrue(reads.loadOwned(loser.request().bookingCode(), owner).isEmpty());
        assertEquals(1, countKeys(owner, key));
        assertEquals(1L, pending(owner));
    }

    /** Kiểm PostgreSQL báo đang đợi Lock, không dùng một khoảng sleep rồi giả định đã tranh chấp. */
    private void waitForBlockedKey() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(jdbc.getJdbcTemplate().queryForObject(
                    sql.load("sql/booking/has_waiting_idempotency_for_test.sql"), Boolean.class))) return;
            TimeUnit.MILLISECONDS.sleep(10);
        }
        fail("The second transaction did not wait for the idempotency key lock.");
    }

    /** Mỗi worker sở hữu transaction riêng có hạn chờ. */
    private TransactionTemplate transaction() {
        var tx = new TransactionTemplate(transactions);
        tx.setTimeout(20);
        return tx;
    }

    /** Đếm key sau commit/rollback từ connection độc lập. */
    private int countKeys(String owner, String key) {
        return jdbc.queryForObject(sql.load("sql/booking/count_idempotency_for_adapter_test.sql"),
                Map.of("customerAccountCode", owner, "key", key), Integer.class);
    }

    /** Đếm đơn chờ bằng SQL thay vì suy từ boolean trả về của hai worker. */
    private long pending(String owner) {
        return jdbc.queryForObject(sql.load("sql/booking/count_pending_for_customer.sql"),
                Map.of("customer_account_code", owner), Long.class);
    }

    /** Chờ barrier có hạn, lỗi đồng bộ phải làm test đỏ. */
    private static void await(CyclicBarrier barrier) {
        try { barrier.await(10, TimeUnit.SECONDS); }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Booking race interrupted.", failure);
        } catch (Exception failure) { throw new IllegalStateException("Booking race synchronization failed.", failure); }
    }

    /** Chờ tín hiệu rollback của luồng điều khiển, không bỏ qua timeout. */
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Booking race release timed out."); }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Booking race interrupted.", failure);
        }
    }

    /** Kết quả worker giữ cả request và claim thực nhận để không nhầm mã đề nghị với mã thắng. */
    private record Outcome(BookingIdempotency request, BookingIdempotencyClaim result, PendingBookingExistsException failure) { }
}
