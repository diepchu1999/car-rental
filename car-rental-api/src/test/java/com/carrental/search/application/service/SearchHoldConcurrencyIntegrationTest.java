package com.carrental.search.application.service;

import com.carrental.PostgresTestConfiguration;
import com.carrental.availability.api.AvailabilityDirectory;
import com.carrental.availability.api.Period;
import com.carrental.availability.api.ReservationRef;
import com.carrental.booking.api.RentalTermsDirectory;
import com.carrental.search.SearchIntegrationTestConfiguration;
import com.carrental.search.SearchTestQueries;
import com.carrental.search.application.port.in.SearchVehiclesUseCase;
import com.carrental.search.application.query.SearchVehiclesQuery;
import com.carrental.search.application.view.SearchVehicleListItem;
import com.carrental.search.domain.VehicleSearchFilters;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.sql.SqlLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BR-104/125, ADR-0005: kết quả search không phải một cam kết giữ chỗ.
 * Hai khách đọc xong rồi mới cùng ghi trên hai kết nối thật; chỉ tính thắng sau commit.
 * Container dành riêng cho test, đóng khi hết lớp; không có transaction bao quanh phương thức test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "car-rental.availability.hold-duration=PT1H")
@Import({PostgresTestConfiguration.class, SearchIntegrationTestConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class SearchHoldConcurrencyIntegrationTest {
    @Autowired private SearchVehiclesUseCase search;
    @Autowired private AvailabilityDirectory availability;
    @Autowired private RentalTermsDirectory terms;
    @Autowired private SearchIntegrationTestConfiguration.Fixtures fixtures;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private NamedParameterJdbcTemplate jdbc;
    @Autowired private SqlLoader sqlLoader;

    /**
     * Mười cuộc đua trên mười xe: mỗi lần hai kết quả search đều có xe,
     * đúng một giữ chỗ commit, bên thua là VEHICLE_NOT_AVAILABLE và CSDL không có chồng lịch.
     * Lỗi timeout, 40P01 hoặc lỗi hạ tầng khác thoát qua Future và làm test đỏ.
     */
    @Test
    void twoCustomersSeeSameVehicleButOnlyOneHoldCommits() throws Exception {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        // Phép thử đảo: chỉ bỏ chú thích dòng dưới trong container test, rồi khôi phục sau khi test đỏ.
        // jdbc.getJdbcTemplate().execute(sqlLoader.load("sql/availability/drop_overlap_constraint_for_search_inverse_test.sql"));
        for (int round = 0; round < 10; round++) {
            var input = SearchTestQueries.input();
            String model = "RaceModel-" + UUID.randomUUID();
            input.filters = new VehicleSearchFilters(null, null, null, null, model, null);
            var branch = fixtures.branch(input.latitude, input.longitude);
            var vehicle = fixtures.active(branch.code(), model);
            var query = input.build();
            List<Attempt> attempts = race(query, vehicle.id(), vehicle.code());
            var winners = attempts.stream().filter(attempt -> attempt.reference() != null).toList();
            assertEquals(1, winners.size(), "Exactly one hold must commit after both customers saw the vehicle.");
            var failures = attempts.stream().filter(attempt -> attempt.failure() != null).toList();
            assertEquals(1, failures.size());
            assertEquals(ErrorCode.VEHICLE_NOT_AVAILABLE, failures.getFirst().failure().errorCode());
            assertEquals(DomainException.Category.CONFLICT, failures.getFirst().failure().category());
            assertCommittedWinner(query, vehicle.id(), winners.getFirst());
            assertTrue(search.search(query).items().isEmpty(), "After commit a fresh search must hide the held vehicle.");
        }
    }

    /** Giữ cả hai kết nối trước khi nhả chốt; mọi chờ đợi có hạn và worker luôn được thu hồi. */
    private List<Attempt> race(SearchVehiclesQuery query, long vehicleId, String vehicleCode) throws Exception {
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2,
                Thread.ofPlatform().name("search-hold-test-", 0).daemon(true).factory());
        List<CompletableFuture<Integer>> connections = new ArrayList<>();
        List<Future<Attempt>> results = new ArrayList<>();
        Throwable originalFailure = null;
        try {
            for (int customer = 0; customer < 2; customer++) {
                var ready = new CompletableFuture<Integer>();
                connections.add(ready);
                String bookingCode = "search-customer-" + UUID.randomUUID();
                results.add(workers.submit(() -> searchThenHold(query, vehicleId, vehicleCode,
                        bookingCode, ready, start)));
            }
            CompletableFuture.allOf(connections.toArray(CompletableFuture<?>[]::new)).get(30, TimeUnit.SECONDS);
            assertEquals(2, connections.stream().map(CompletableFuture::join).distinct().count(),
                    "Both customers must own distinct PostgreSQL connections before writing.");
            start.countDown();
            List<Attempt> attempts = new ArrayList<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
            for (Future<Attempt> result : results) {
                long remaining = deadline - System.nanoTime();
                assertTrue(remaining > 0, "The search/hold race exceeded its deadline.");
                attempts.add(result.get(remaining, TimeUnit.NANOSECONDS));
            }
            return attempts;
        } catch (Exception | Error failure) {
            originalFailure = failure;
            throw failure;
        } finally {
            for (Future<Attempt> result : results) {
                if (!result.isDone()) {
                    result.cancel(true);
                }
            }
            workers.shutdownNow();
            start.countDown();
            try {
                assertTrue(workers.awaitTermination(30, TimeUnit.SECONDS), "Search/hold workers did not stop.");
            } catch (InterruptedException | AssertionError cleanupFailure) {
                if (cleanupFailure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                if (originalFailure != null) {
                    originalFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    /**
     * Search hoàn tất transaction đọc trước khi mở transaction ghi riêng của khách.
     * Chốt chỉ phục vụ điều phối test, không thay cơ chế ghi trực tiếp của availability.
     * Chỉ bắt DomainException sau rollback; mọi lỗi hạ tầng giữ nguyên.
     */
    private Attempt searchThenHold(SearchVehiclesQuery query, long vehicleId, String vehicleCode,
            String bookingCode, CompletableFuture<Integer> ready, CountDownLatch start) {
        try {
            assertEquals(List.of(vehicleCode), search.search(query).items().stream()
                    .map(SearchVehicleListItem::code).toList(), "Both customers must see the same available vehicle.");
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            var buffer = terms.getTerms(query.rentalType(), query.pickupMethod(),
                    query.startInclusive(), query.endExclusive()).turnaroundBuffer();
            ReservationRef reference = new TransactionTemplate(transactionManager).execute(status -> {
                int pid = Objects.requireNonNull(jdbc.queryForObject(
                        sqlLoader.load("sql/availability/prepare_concurrent_hold_for_test.sql"),
                        Map.of(), (rs, rowNum) -> rs.getInt("backend_pid")));
                ready.complete(pid);
                awaitStart(start);
                return availability.hold(vehicleId, new Period(query.startInclusive(), query.endExclusive()),
                        buffer, bookingCode);
            });
            return new Attempt(bookingCode, Objects.requireNonNull(reference), null);
        } catch (DomainException failure) {
            ready.completeExceptionally(failure);
            return new Attempt(bookingCode, null, failure);
        } catch (RuntimeException | Error failure) {
            ready.completeExceptionally(failure);
            throw failure;
        }
    }

    /** Chờ chốt hữu hạn; ngắt luồng làm rollback, không được biến thành xe bận. */
    private static void awaitStart(CountDownLatch start) {
        try {
            assertTrue(start.await(35, TimeUnit.SECONDS), "Timed out waiting for both customers.");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted before concurrent hold.", failure);
        }
    }

    /** Đọc độc lập sau cả hai transaction: đúng người thắng, đúng khoảng có đệm và không có cặp chồng lịch. */
    private void assertCommittedWinner(SearchVehiclesQuery query, long vehicleId, Attempt winner) {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        var rows = jdbc.query(sqlLoader.load("sql/availability/read_committed_holds_for_test.sql"),
                Map.of("vehicleIds", List.of(vehicleId)), (rs, rowNum) -> new StoredHold(
                        rs.getLong("id"), rs.getString("code"), rs.getString("booking_code"),
                        rs.getString("kind"), rs.getString("status"),
                        rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("ends_at", OffsetDateTime.class).toInstant(),
                        rs.getBoolean("start_inclusive"), rs.getBoolean("end_inclusive")));
        assertEquals(1, rows.size(), "The losing transaction must not leave a reservation row.");
        var row = rows.getFirst();
        assertEquals(winner.reference().id(), row.id());
        assertEquals(winner.reference().code(), row.code());
        assertEquals(winner.bookingCode(), row.bookingCode());
        assertEquals("RENTAL", row.kind());
        assertEquals("HELD", row.status());
        assertTrue(row.startInclusive());
        assertFalse(row.endInclusive());
        assertEquals(query.startInclusive(), row.start());
        var buffer = terms.getTerms(query.rentalType(), query.pickupMethod(),
                query.startInclusive(), query.endExclusive()).turnaroundBuffer();
        assertEquals(query.endExclusive().plus(buffer), row.end());
        assertEquals(0L, jdbc.queryForObject(
                sqlLoader.load("sql/availability/count_overlapping_blocking_reservations_for_test.sql"),
                Map.of("vehicleId", vehicleId), Long.class));
    }

    /** Kết quả chỉ được tạo sau commit/rollback, không đếm thành công ngay trong callback JDBC. */
    private record Attempt(String bookingCode, ReservationRef reference, DomainException failure) {
    }

    /** Bản ghi đọc lại bằng kết nối độc lập, gồm cả cận đóng/mở và phần đệm đã lưu. */
    private record StoredHold(long id, String code, String bookingCode, String kind, String status,
            Instant start, Instant end, boolean startInclusive, boolean endInclusive) {
    }
}
