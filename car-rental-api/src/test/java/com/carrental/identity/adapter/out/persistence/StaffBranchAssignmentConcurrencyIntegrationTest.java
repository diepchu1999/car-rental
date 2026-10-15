package com.carrental.identity.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import com.carrental.identity.application.port.out.ReadStaffBranchAssignmentPort;
import com.carrental.identity.application.port.out.WriteStaffBranchAssignmentPort;
import com.carrental.identity.domain.AccountRole;
import com.carrental.identity.domain.StaffBranchAssignment;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BR-807: hai transaction giữ kết nối riêng trước khi cùng INSERT; chỉ tính thành công sau commit.
 * Kiểm ràng buộc CSDL và adapter thật, chưa phải test API. Không bọc phương thức test trong transaction.
 * Context riêng được đóng sau lớp để phép thử đảo không ảnh hưởng các lớp khác hoặc CSDL local.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.datasource.hikari.maximum-pool-size=4", "spring.datasource.hikari.connection-timeout=10000"})
@Import(PostgresTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class StaffBranchAssignmentConcurrencyIntegrationTest extends IdentityDatabaseTestSupport {
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired WriteStaffBranchAssignmentPort write;
    @Autowired ReadStaffBranchAssignmentPort read;

    /** Hai adapter cùng gán quản lý: một commit, một lỗi nghiệp vụ đúng mã, không để lỗi JDBC lọt ra. */
    @Test
    void concurrentAdaptersReportExactlyOneManagerConflict() throws Exception {
        var fixtures = List.of(adapterFixture(936001L, AccountRole.BRANCH_MANAGER),
                adapterFixture(936001L, AccountRole.BRANCH_MANAGER));
        var attempts = runAdaptersTogether(fixtures);
        var winners = attempts.stream().filter(Attempt::committed).toList();
        assertEquals(1, winners.size());
        assertEquals(1, attempts.stream().filter(attempt -> !attempt.committed()).count());
        assertEquals(List.of(winners.getFirst().assignment()), readAssignments(List.of(936001L)));
        for (AdapterFixture fixture : fixtures) {
            var saved = read.findByAccountCode(fixture.value().accountCode());
            if (fixture.command().equals(winners.getFirst().assignment())) {
                assertEquals(fixture.value(), saved.orElseThrow());
            } else {
                assertTrue(saved.isEmpty());
            }
        }
    }

    /** Hai adapter gán nhân viên thường cùng chi nhánh đều commit và đọc lại đầy đủ. */
    @Test
    void concurrentAdaptersPermitTwoStaffInOneBranch() throws Exception {
        var fixtures = List.of(adapterFixture(936002L, AccountRole.BRANCH_STAFF),
                adapterFixture(936002L, AccountRole.BRANCH_STAFF));
        var attempts = runAdaptersTogether(fixtures);
        assertTrue(attempts.stream().allMatch(Attempt::committed));
        assertEquals(2, readAssignments(List.of(936002L)).size());
        for (AdapterFixture fixture : fixtures) {
            assertEquals(fixture.value(), read.findByAccountCode(fixture.value().accountCode()).orElseThrow());
        }
    }

    /**
     * Chi nhánh chưa có quản lý hợp lệ; khi hai quản lý cùng được gán thì đúng một commit.
     * Phép thử đảo: chỉ bỏ comment đúng dòng DROP INDEX dưới, chạy riêng method này rồi khôi phục.
     * Không sửa V010 đã chạy và không thực hiện DROP INDEX trong SQL console/local.
     */
    @Test
    void twoManagersForOneBranchProduceExactlyOneCommittedWinner() throws Exception {
        long branch = 920001;
        assertTrue(readAssignments(List.of(branch)).isEmpty());
        List<Assignment> commands = List.of(new Assignment(newAccount(), branch, "BRANCH_MANAGER"),
                new Assignment(newAccount(), branch, "BRANCH_MANAGER"));

        // jdbc.getJdbcTemplate().execute("DROP INDEX identity.uq_staff_branch_manager");

        List<Attempt> attempts = runTogether(commands);
        List<Attempt> winners = attempts.stream().filter(Attempt::committed).toList();
        assertEquals(1, winners.size(), "Exactly one manager transaction must commit.");
        assertEquals(1, attempts.stream().filter(attempt -> !attempt.committed()).count());
        assertEquals(List.of(winners.getFirst().assignment()), readAssignments(List.of(branch)),
                "The committed row must belong to the winner, with no row left by the loser.");
    }

    /** Index chỉ chặn quản lý: hai nhân viên cùng chi nhánh đều commit. */
    @Test
    void twoStaffForOneBranchBothCommit() throws Exception {
        assertBothCommit(List.of(new Assignment(newAccount(), 920002, "BRANCH_STAFF"),
                new Assignment(newAccount(), 920002, "BRANCH_STAFF")));
    }

    /** Hai chi nhánh khác nhau có hai quản lý độc lập, không bị một khoá chung chặn. */
    @Test
    void managersForDifferentBranchesBothCommit() throws Exception {
        assertBothCommit(List.of(new Assignment(newAccount(), 920003, "BRANCH_MANAGER"),
                new Assignment(newAccount(), 920004, "BRANCH_MANAGER")));
    }

    /** Đọc độc lập sau hai commit để kiểm đủ đúng bản ghi, không chỉ kiểm số exception. */
    private void assertBothCommit(List<Assignment> commands) throws Exception {
        List<Attempt> attempts = runTogether(commands);
        assertTrue(attempts.stream().allMatch(Attempt::committed));
        List<Assignment> expected = commands.stream().sorted(java.util.Comparator.comparingLong(Assignment::accountId)).toList();
        assertEquals(expected, readAssignments(commands.stream().map(Assignment::branchId).distinct().toList()));
    }

    /** Giữ hai connection thật, nhả chốt chung, chờ có giới hạn và luôn thu hồi worker. */
    private List<Attempt> runTogether(List<Assignment> commands) throws Exception {
        return runTogether(commands, command -> insertAssignment(
                assignmentParameters(command.accountId(), command.branchId(), command.role())),
                StaffBranchAssignmentConcurrencyIntegrationTest::assertManagerConstraint);
    }

    /** Chạy cùng cơ chế hai connection với thao tác ghi và tiêu chí lỗi cụ thể của từng tầng. */
    private List<Attempt> runTogether(List<Assignment> commands, Consumer<Assignment> operation,
                                     Consumer<RuntimeException> verifyConflict) throws Exception {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        CountDownLatch start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2,
                Thread.ofPlatform().name("identity-assignment-test-", 0).daemon(true).factory());
        List<CompletableFuture<Integer>> connections = new ArrayList<>();
        List<Future<Attempt>> results = new ArrayList<>();
        Throwable originalFailure = null;
        try {
            for (Assignment command : commands) {
                var ready = new CompletableFuture<Integer>();
                connections.add(ready);
                results.add(workers.submit(() -> assignInTransaction(command, ready, start, operation, verifyConflict)));
            }
            CompletableFuture.allOf(connections.toArray(CompletableFuture<?>[]::new)).get(20, TimeUnit.SECONDS);
            assertEquals(2, connections.stream().map(CompletableFuture::join).distinct().count(),
                    "Contenders must hold two distinct PostgreSQL connections before writing.");
            start.countDown();
            List<Attempt> attempts = new ArrayList<>();
            for (Future<Attempt> result : results) attempts.add(result.get(30, TimeUnit.SECONDS));
            return attempts;
        } catch (Exception | Error failure) {
            originalFailure = failure;
            throw failure;
        } finally {
            for (Future<Attempt> result : results) if (!result.isDone()) result.cancel(true);
            workers.shutdownNow();
            start.countDown();
            try {
                assertTrue(workers.awaitTermination(25, TimeUnit.SECONDS), "Assignment workers did not stop.");
            } catch (InterruptedException | AssertionError cleanupFailure) {
                if (cleanupFailure instanceof InterruptedException) Thread.currentThread().interrupt();
                if (originalFailure != null) originalFailure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
    }

    /** Chỉ nhận lỗi thua đã được kiểm chính xác; timeout/deadlock hoặc lỗi khác phải làm test đỏ. */
    private Attempt assignInTransaction(Assignment command, CompletableFuture<Integer> ready, CountDownLatch start,
                                         Consumer<Assignment> operation, Consumer<RuntimeException> verifyConflict) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setTimeout(30);
        try {
            transaction.executeWithoutResult(status -> {
                Integer pid = jdbc.queryForObject(sqlLoader.load("sql/identity/prepare_concurrent_assignment_for_test.sql"),
                        Map.of(), (rs, row) -> rs.getInt("backend_pid"));
                assertNotNull(pid);
                ready.complete(pid);
                awaitStart(start);
                operation.accept(command);
            });
            return new Attempt(command, true);
        } catch (RuntimeException failure) {
            ready.completeExceptionally(failure);
            verifyConflict.accept(failure);
            return new Attempt(command, false);
        } catch (Error failure) {
            ready.completeExceptionally(failure);
            throw failure;
        }
    }

    /** Giữ phép kiểm SQLSTATE/schema/bảng/index của các test ràng buộc gốc, không nới tiêu chí. */
    private static void assertManagerConstraint(RuntimeException failure) {
        var integrity = assertInstanceOf(DataIntegrityViolationException.class, failure);
        var postgres = assertInstanceOf(PSQLException.class, integrity.getMostSpecificCause());
        assertEquals("23505", postgres.getSQLState());
        var details = postgres.getServerErrorMessage();
        assertNotNull(details);
        assertEquals("identity", details.getSchema());
        assertEquals("staff_branch_assignment", details.getTable());
        assertEquals("uq_staff_branch_manager", details.getConstraint());
    }

    /** Fixture giữ ID chỉ cho kiểm SQL độc lập; adapter nhận mã nghiệp vụ qua domain. */
    private AdapterFixture adapterFixture(long branchId, AccountRole role) {
        var parameters = accountParameters();
        long accountId = insertAccount(parameters);
        return new AdapterFixture(new Assignment(accountId, branchId, role.name()),
                new StaffBranchAssignment((String) parameters.getValue("code"), branchId, role, Instant.parse(AT)));
    }

    /** Không mock port: gọi bean adapter thật và chỉ chấp nhận BRANCH_MANAGER_ALREADY_ASSIGNED. */
    private List<Attempt> runAdaptersTogether(List<AdapterFixture> fixtures) throws Exception {
        var values = fixtures.stream().collect(Collectors.toMap(fixture -> fixture.command().accountId(), AdapterFixture::value));
        return runTogether(fixtures.stream().map(AdapterFixture::command).toList(),
                command -> assertTrue(write.save(values.get(command.accountId()))), failure -> {
                    var conflict = assertInstanceOf(DomainException.class, failure);
                    assertEquals(ErrorCode.BRANCH_MANAGER_ALREADY_ASSIGNED, conflict.errorCode());
                    assertEquals(DomainException.Category.CONFLICT, conflict.category());
                });
    }

    /** Timeout hoặc interrupt trước cuộc đua phải rollback, không bị coi là từ chối nghiệp vụ. */
    private static void awaitStart(CountDownLatch start) {
        try {
            assertTrue(start.await(20, TimeUnit.SECONDS), "Concurrent assignment start timed out.");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Concurrent assignment interrupted.", failure);
        }
    }

    /** Đọc ngoài transaction worker, thấy chỉ các bản ghi đã commit. */
    private List<Assignment> readAssignments(List<Long> branches) {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        return jdbc.query(sqlLoader.load("sql/identity/read_assignments_for_test.sql"), Map.of("branchIds", branches),
                (rs, row) -> new Assignment(rs.getLong("account_id"), rs.getLong("branch_id"), rs.getString("staff_role")));
    }

    /** Dữ liệu của một thao tác gán trong test, không phải request API. */
    private record Assignment(long accountId, long branchId, String role) { }

    /** Hai cách nhìn cùng fixture: kỳ vọng SQL độc lập và đầu vào domain của adapter. */
    private record AdapterFixture(Assignment command, StaffBranchAssignment value) { }

    /** Chỉ đặt committed sau khi TransactionTemplate đã hoàn tất commit. */
    private record Attempt(Assignment assignment, boolean committed) { }
}
