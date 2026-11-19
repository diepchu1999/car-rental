package com.carrental.booking.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import com.carrental.shared.sql.SqlLoader;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.carrental.booking.adapter.out.persistence.BookingDatabaseFixtures.header;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Hai transaction PostgreSQL thật kiểm riêng cơ chế BR-131 ở nền schema.
 * Không thay thế test tạo đơn qua use case/giữ chỗ/idempotency sẽ thêm ở bước nối luồng.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
class BookingSchemaConcurrencyIntegrationTest {
    @Autowired private NamedParameterJdbcTemplate jdbc;
    @Autowired private SqlLoader sql;
    @Autowired private PlatformTransactionManager transactions;

    /** Hai INSERT đã vào transaction trước khi thả cùng lúc; chỉ một commit cho cùng khách. */
    @Test
    void sameCustomerCanCommitOnlyOnePendingBooking() throws Exception {
        var outcomes = race("TK-RACE01", "TK-RACE01", "RACE01", "RACE02");
        assertEquals(1L, outcomes.stream().filter("COMMITTED"::equals).count());
        assertEquals(1L, outcomes.stream().filter("23505:uq_booking_pending_customer"::equals).count());
        assertEquals(1L, pending("TK-RACE01"));
    }

    /** Cơ chế chỉ giới hạn theo khách; hai khách độc lập đều ghi được đơn chờ. */
    @Test
    void differentCustomersCanBothCommit() throws Exception {
        assertEquals(List.of("COMMITTED", "COMMITTED"), race("TK-RACE03", "TK-RACE04", "RACE03", "RACE04"));
        assertEquals(1L, pending("TK-RACE03"));
        assertEquals(1L, pending("TK-RACE04"));
    }

    /** Hai worker và một barrier trước INSERT, có giới hạn chờ để lỗi không treo bộ test. */
    private List<String> race(String firstOwner, String secondOwner, String firstSuffix, String secondSuffix) throws Exception {
        var barrier = new CyclicBarrier(2);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> insertAtBarrier(firstOwner, firstSuffix, barrier));
            var second = workers.submit(() -> insertAtBarrier(secondOwner, secondSuffix, barrier));
            return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS), "Booking race workers did not stop.");
        }
    }

    /** Lấy connection thật trước barrier; không giữ transaction của test ở luồng cha. */
    private String insertAtBarrier(String owner, String suffix, CyclicBarrier barrier) {
        var transaction = new TransactionTemplate(transactions);
        transaction.setTimeout(15);
        try {
            transaction.executeWithoutResult(status -> {
                await(barrier);
                jdbc.queryForObject(sql.load("sql/booking/insert_booking_for_constraint_test.sql"), header()
                        .addValue("customer_account_code", owner).addValue("code", "DT-2611-" + suffix)
                        .addValue("reservation_code", "KL-" + suffix), Long.class);
            });
            return "COMMITTED";
        } catch (DataIntegrityViolationException failure) {
            var cause = assertInstanceOf(PSQLException.class, failure.getMostSpecificCause());
            assertNotNull(cause.getServerErrorMessage());
            assertEquals("booking", cause.getServerErrorMessage().getSchema());
            assertEquals("booking", cause.getServerErrorMessage().getTable());
            return cause.getSQLState() + ":" + cause.getServerErrorMessage().getConstraint();
        }
    }

    /** Đọc từ connection độc lập sau khi cả hai transaction đã commit/rollback. */
    private long pending(String owner) {
        return jdbc.queryForObject(sql.load("sql/booking/count_pending_for_customer.sql"),
                new MapSqlParameterSource("customer_account_code", owner), Long.class);
    }

    /** Barrier có hạn; lỗi đồng bộ làm test đỏ, không đổi thành lỗi nghiệp vụ. */
    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Booking race interrupted.", failure);
        } catch (Exception failure) {
            throw new IllegalStateException("Booking race could not start both transactions.", failure);
        }
    }
}
