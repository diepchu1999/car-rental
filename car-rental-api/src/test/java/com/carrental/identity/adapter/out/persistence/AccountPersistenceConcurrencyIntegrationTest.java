package com.carrental.identity.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import com.carrental.identity.application.command.ResolveAccountCommand;
import com.carrental.identity.application.port.in.ResolveAccountUseCase;
import com.carrental.identity.application.port.out.ReadAccountPort;
import com.carrental.identity.application.port.out.WriteAccountPort;
import com.carrental.identity.application.view.AccountDetail;
import com.carrental.identity.domain.Account;
import com.carrental.identity.domain.AccountRole;
import com.carrental.identity.domain.TermsConsent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.carrental.identity.application.port.out.WriteAccountPort.InsertResult.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * BR-120: kiểm adapter thật ở READ_COMMITTED trên hai connection/transaction, không mock persistence.
 * Không có transaction bao phương thức test. Kết quả chỉ trả sau commit; mọi lỗi hạ tầng làm test đỏ.
 * Kiểm cả adapter và use case lần đầu; chưa kiểm JWT hoặc HTTP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.datasource.hikari.maximum-pool-size=4", "spring.datasource.hikari.connection-timeout=10000"})
@Import(PostgresTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class AccountPersistenceConcurrencyIntegrationTest extends IdentityDatabaseTestSupport {
    private static final Instant CREATED_AT = Instant.parse(AT);
    @Autowired WriteAccountPort write;
    @Autowired ReadAccountPort read;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ResolveAccountUseCase resolve;

    /** Hai use case trên hai transaction trả cùng view đã lưu, không để bên thua nhận lỗi UNIQUE. */
    @Test
    void concurrentFirstUseCasesReturnOneSharedAccount() throws Exception {
        Account identity = accountFixture(null);
        var command = ResolveAccountCommand.from(identity.keycloakSubject(), identity.phoneNumber().value(),
                Set.of(AccountRole.CUSTOMER), "local-test-v1", CREATED_AT);
        var views = new ConcurrentLinkedQueue<AccountDetail>();
        Supplier<Boolean> operation = () -> { views.add(resolve.resolve(command)); return true; };
        var attempts = runTogether(identity.keycloakSubject(), List.of(operation, operation));
        assertEquals(2, views.size());
        assertEquals(1, views.stream().distinct().count());
        AccountDetail winning = views.element();
        for (Attempt attempt : attempts) assertEquals(winning, AccountDetail.from(attempt.account()));
        var rows = jdbc.queryForList(sqlLoader.load("sql/identity/read_account_by_subject_for_test.sql"),
                Map.of("subject", identity.keycloakSubject()));
        assertEquals(1, rows.size());
        assertEquals(winning.code(), rows.getFirst().get("code"));
        assertEquals("local-test-v1", winning.termsVersion());
        assertEquals(CREATED_AT, winning.consentedAt());
    }

    /** Hai use case bổ sung consent không trộn cặp bằng chứng và cùng trả đúng bản đã commit. */
    @Test
    void concurrentUseCasesReturnTheWinningConsentPair() throws Exception {
        Account employee = accountFixture(null);
        assertEquals(INSERTED, write.insert(employee, CREATED_AT));
        var firstConsent = new TermsConsent("local-test-v1", CREATED_AT.minusSeconds(60));
        var secondConsent = new TermsConsent("local-test-v2", CREATED_AT.minusSeconds(30));
        var views = new ConcurrentLinkedQueue<AccountDetail>();
        List<Supplier<Boolean>> operations = List.of(firstConsent, secondConsent).stream()
                .<Supplier<Boolean>>map(consent -> () -> {
                    views.add(resolve.resolve(ResolveAccountCommand.from(employee.keycloakSubject(), employee.phoneNumber().value(),
                            Set.of(AccountRole.CUSTOMER, AccountRole.BRANCH_STAFF), consent.version(), consent.consentedAt())));
                    return true;
                }).toList();
        var attempts = runTogether(employee.keycloakSubject(), operations);
        assertEquals(2, views.size());
        assertEquals(1, views.stream().distinct().count());
        AccountDetail winning = views.element();
        assertEquals(employee.code(), winning.code());
        assertTrue(List.of(firstConsent, secondConsent).contains(new TermsConsent(winning.termsVersion(), winning.consentedAt())));
        for (Attempt attempt : attempts) assertEquals(winning, AccountDetail.from(attempt.account()));
        jdbc.queryForObject(sqlLoader.load("sql/identity/read_account_by_subject_for_test.sql"),
                Map.of("subject", employee.keycloakSubject()), (rs, row) -> {
                    assertEquals(winning.termsVersion(), rs.getString("terms_version"));
                    assertEquals(winning.consentedAt(), rs.getObject("consented_at", OffsetDateTime.class).toInstant());
                    assertEquals(CREATED_AT, rs.getObject("created_at", OffsetDateTime.class).toInstant());
                    return true;
                });
    }

    /** Cùng subject/số, hai mã khác nhau: một INSERTED, một SUBJECT_EXISTS, cả hai đọc được mã thắng. */
    @Test
    void concurrentFirstInsertsReturnOneSharedAccountWithoutFailure() throws Exception {
        var consent = new TermsConsent("local-test-v1", CREATED_AT.minusSeconds(60));
        Account first = accountFixture(consent);
        Account second = Account.restore(accountFixture(null).code(), first.keycloakSubject(), first.phoneNumber(), consent);
        List<Attempt> attempts = runTogether(first.keycloakSubject(), List.of(() -> insert(first), () -> insert(second)));
        assertEquals(1, attempts.stream().filter(Attempt::changed).count());
        String winningCode = attempts.stream().filter(Attempt::changed).findFirst().orElseThrow().account().code();
        assertTrue(List.of(first.code(), second.code()).contains(winningCode));
        for (Attempt attempt : attempts) {
            assertEquals(winningCode, attempt.account().code());
            assertEquals(first.phoneNumber(), attempt.account().phoneNumber());
            assertEquals(consent, attempt.account().consent().orElseThrow());
        }
        var rows = jdbc.queryForList(sqlLoader.load("sql/identity/read_account_by_subject_for_test.sql"),
                Map.of("subject", first.keycloakSubject()));
        assertEquals(1, rows.size());
        assertEquals(winningCode, rows.getFirst().get("code"));
    }

    /** Hai bằng chứng khác nhau tranh cùng tài khoản: giữ trọn cặp của một bên, không trộn hoặc ghi đè. */
    @Test
    void concurrentConsentUpdatesPreserveOneWholeEvidencePair() throws Exception {
        Account employee = accountFixture(null);
        assertEquals(INSERTED, write.insert(employee, CREATED_AT));
        var firstConsent = new TermsConsent("local-test-v1", CREATED_AT.minusSeconds(60));
        var secondConsent = new TermsConsent("local-test-v2", CREATED_AT.minusSeconds(30));
        Account first = Account.restore(employee.code(), employee.keycloakSubject(), employee.phoneNumber(), firstConsent);
        Account second = Account.restore(employee.code(), employee.keycloakSubject(), employee.phoneNumber(), secondConsent);
        List<Attempt> attempts = runTogether(employee.keycloakSubject(),
                List.of(() -> write.addConsentIfAbsent(first), () -> write.addConsentIfAbsent(second)));
        assertEquals(1, attempts.stream().filter(Attempt::changed).count());
        TermsConsent winning = attempts.get(0).changed() ? firstConsent : secondConsent;
        for (Attempt attempt : attempts) assertEquals(winning, attempt.account().consent().orElseThrow());
        jdbc.queryForObject(sqlLoader.load("sql/identity/read_account_by_subject_for_test.sql"),
                Map.of("subject", employee.keycloakSubject()), (rs, row) -> {
                    assertEquals(winning.version(), rs.getString("terms_version"));
                    assertEquals(winning.consentedAt(), rs.getObject("consented_at", OffsetDateTime.class).toInstant());
                    assertEquals(CREATED_AT, rs.getObject("created_at", OffsetDateTime.class).toInstant());
                    return employee.code();
                });
    }

    /** Không cho test bỏ qua CODE_COLLISION hoặc lỗi lưu trữ như thể đó là cùng một danh tính. */
    private boolean insert(Account account) {
        var result = write.insert(account, CREATED_AT);
        assertTrue(result == INSERTED || result == SUBJECT_EXISTS, "Unexpected account insert result.");
        return result == INSERTED;
    }

    /** Mở chốt sau khi hai PID đã sẵn sàng; thu kết quả sau commit, có timeout và dọn worker. */
    private List<Attempt> runTogether(String subject, List<Supplier<Boolean>> operations) throws Exception {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        CountDownLatch start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2,
                Thread.ofPlatform().name("identity-account-test-", 0).daemon(true).factory());
        List<CompletableFuture<Integer>> connections = new ArrayList<>();
        List<Future<Attempt>> results = new ArrayList<>();
        Throwable originalFailure = null;
        try {
            for (Supplier<Boolean> operation : operations) {
                var ready = new CompletableFuture<Integer>();
                connections.add(ready);
                results.add(workers.submit(() -> transact(subject, operation, ready, start)));
            }
            CompletableFuture.allOf(connections.toArray(CompletableFuture<?>[]::new)).get(20, TimeUnit.SECONDS);
            assertEquals(2, connections.stream().map(CompletableFuture::join).distinct().count());
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
                assertTrue(workers.awaitTermination(25, TimeUnit.SECONDS), "Account workers did not stop.");
            } catch (InterruptedException | AssertionError cleanupFailure) {
                if (cleanupFailure instanceof InterruptedException) Thread.currentThread().interrupt();
                if (originalFailure != null) originalFailure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
    }

    /** Một transaction cho cả ghi và đọc sau; không đọc trước để quyết định danh tính có tồn tại. */
    private Attempt transact(String subject, Supplier<Boolean> operation, CompletableFuture<Integer> ready, CountDownLatch start) {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(30);
        try {
            Attempt attempt = transaction.execute(status -> {
                Integer pid = jdbc.queryForObject(sqlLoader.load("sql/identity/prepare_concurrent_assignment_for_test.sql"),
                        Map.of(), (rs, row) -> rs.getInt("backend_pid"));
                assertNotNull(pid);
                ready.complete(pid);
                awaitStart(start);
                boolean changed = operation.get();
                return new Attempt(changed, read.findBySubject(subject).orElseThrow());
            });
            assertNotNull(attempt);
            return attempt;
        } catch (RuntimeException | Error failure) {
            ready.completeExceptionally(failure);
            throw failure;
        }
    }

    /** Ngắt hoặc timeout làm test đỏ và rollback, không được giả thành thao tác không thay đổi. */
    private static void awaitStart(CountDownLatch start) {
        try {
            assertTrue(start.await(20, TimeUnit.SECONDS), "Concurrent account start timed out.");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Concurrent account operation interrupted.", failure);
        }
    }

    /** Kết quả từ transaction đã commit, gồm dữ liệu đọc sau thao tác ghi. */
    private record Attempt(boolean changed, Account account) { }
}
