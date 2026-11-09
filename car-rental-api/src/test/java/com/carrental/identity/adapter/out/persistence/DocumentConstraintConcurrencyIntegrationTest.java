package com.carrental.identity.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Hai connection/transaction thật chứng minh UNIQUE của V012; không có transaction bao toàn test. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.datasource.hikari.maximum-pool-size=4", "spring.datasource.hikari.connection-timeout=10000"})
@Import(PostgresTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class DocumentConstraintConcurrencyIntegrationTest extends DocumentDatabaseTestSupport {
    @Autowired PlatformTransactionManager transactions;

    /** Cùng khách/loại: một commit, một vi phạm đúng uq_document_submission_pending, đọc lại có đúng một. */
    @Test
    void onlyOneConcurrentPendingSubmissionCanCommit() throws Exception {
        long owner = newAccount();
        var first = submission(owner, "IDENTITY_CARD", "PENDING");
        var second = submission(owner, "IDENTITY_CARD", "PENDING");

        // Phép thử đảo CHỈ trên container test: bỏ comment dòng dưới, test phải đỏ vì cả hai commit.
        // jdbc.getJdbcTemplate().execute("DROP INDEX identity.uq_document_submission_pending");
        // Khôi phục comment ngay sau phép thử; tuyệt đối không chạy câu này trên CSDL local.
        var attempts = race(() -> insert("submission", first), () -> insert("submission", second));
        assertOneWinner(attempts, "uq_document_submission_pending");
        assertEquals(1L, jdbc.queryForObject(documentSql("count_pending"),
                Map.of("accountId", owner, "type", "IDENTITY_CARD"), Long.class));
    }

    /** Cùng upload: chỉ một bản nộp chiếm được lượt tải, không phải cả hai đọc thấy chưa dùng rồi cùng ghi. */
    @Test
    void onlyOneConcurrentSubmissionCanConsumeAnUpload() throws Exception {
        long owner = newAccount();
        long old = insert("submission", submission(owner, "IDENTITY_CARD", "SUPERSEDED"));
        long current = insert("submission", submission(owner, "IDENTITY_CARD", "PENDING"));
        long upload = insert("upload", upload(owner, "IDENTITY_CARD", "FRONT"));
        var first = image(old, upload, owner, "IDENTITY_CARD", "FRONT");
        var second = image(current, upload, owner, "IDENTITY_CARD", "FRONT");

        // Phép thử đảo CHỈ trên container test; khôi phục comment ngay sau khi thấy test đỏ.
        // jdbc.getJdbcTemplate().execute("ALTER TABLE identity.document_image DROP CONSTRAINT uq_document_image_upload");
        var attempts = race(() -> insert("image", first), () -> insert("image", second));
        assertOneWinner(attempts, "uq_document_image_upload");
        assertEquals(1L, jdbc.queryForObject(documentSql("count_upload_uses"), Map.of("uploadId", upload), Long.class));
    }

    /** Mỗi bên mở transaction riêng trước barrier; kết quả thành công chỉ trả sau commit. */
    private List<Attempt> race(Runnable first, Runnable second) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> attempt(first, ready, start));
            var b = executor.submit(() -> attempt(second, ready, start));
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS), "Both database transactions must reach the barrier.");
            } finally {
                start.countDown();
            }
            return List.of(a.get(25, TimeUnit.SECONDS), b.get(25, TimeUnit.SECONDS));
        }
    }

    /** Chỉ ghi nhận lỗi integrity thật; timeout, lỗi kết nối hoặc exception khác không bị tính là bên thua hợp lệ. */
    private Attempt attempt(Runnable operation, CountDownLatch ready, CountDownLatch start) {
        var transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(15);
        try {
            transaction.executeWithoutResult(status -> {
                ready.countDown();
                try {
                    if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Race start barrier timed out.");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Race was interrupted.");
                }
                operation.run();
            });
            return new Attempt(true, null, null);
        } catch (DataIntegrityViolationException failure) {
            var postgres = assertInstanceOf(PSQLException.class, failure.getMostSpecificCause());
            assertNotNull(postgres.getServerErrorMessage());
            return new Attempt(false, postgres.getSQLState(), postgres.getServerErrorMessage().getConstraint());
        }
    }

    /** Không chấp nhận một lỗi SQL bất kỳ làm test xanh; bên thua phải do đúng UNIQUE đang chứng minh. */
    private static void assertOneWinner(List<Attempt> attempts, String constraint) {
        assertEquals(1, attempts.stream().filter(Attempt::committed).count());
        var loser = attempts.stream().filter(attempt -> !attempt.committed()).findFirst().orElseThrow();
        assertEquals("23505", loser.state());
        assertEquals(constraint, loser.constraint());
    }

    /** Kết quả chỉ chứa metadata kỹ thuật, không mang SQL, ảnh hoặc phong bì nhạy cảm vào assertion. */
    private record Attempt(boolean committed, String state, String constraint) { }
}
