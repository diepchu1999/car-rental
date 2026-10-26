package com.carrental.identity.adapter.out.persistence;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.carrental.PostgresTestConfiguration;
import com.carrental.identity.application.port.out.WriteDocumentSubmissionPort;
import com.carrental.identity.application.port.out.WriteDocumentUploadPort;
import com.carrental.identity.domain.DocumentImage;
import com.carrental.identity.domain.DocumentNumber;
import com.carrental.identity.domain.DocumentProtectionContext;
import com.carrental.identity.domain.DocumentSide;
import com.carrental.identity.domain.DocumentSubmission;
import com.carrental.identity.domain.DocumentType;
import com.carrental.identity.domain.DocumentUpload;
import com.carrental.identity.domain.ProtectedDocumentNumber;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.sql.SensitiveSql;
import com.carrental.shared.sql.SensitiveSqlFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

/** BR-130: adapter/transaction/PostgreSQL thật, gồm rollback, tranh chấp và che log giấy tờ. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.datasource.hikari.maximum-pool-size=4", "spring.datasource.hikari.connection-timeout=10000"})
@Import(PostgresTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
@Execution(ExecutionMode.SAME_THREAD)
class DocumentSubmissionPersistenceIntegrationTest extends DocumentDatabaseTestSupport {
    private static final AtomicLong CODES = new AtomicLong();
    private static final Instant NOW = Instant.parse(AT);
    @Autowired WriteDocumentSubmissionPort writes;
    @Autowired WriteDocumentUploadPort uploads;
    @Autowired PlatformTransactionManager transactions;
    @Autowired Environment environment;
    @MockitoSpyBean NamedParameterJdbcTemplate observedJdbc;

    /** Hai loại đều lưu đúng phong bì, đủ hai ảnh và latest; không tự tạo bản VERIFIED. */
    @ParameterizedTest @EnumSource(DocumentType.class)
    void persistsProtectedSubmissionAndBothImages(DocumentType type) {
        String owner = accountCode(newAccount());
        var document = fixture(owner, type);
        assertTrue(writes.insert(document));
        var row = read(document);
        assertEquals("PENDING", row.get("status"));
        assertEquals(2L, row.get("image_count"));
        assertEquals(document.code(), row.get("latest_code"));
        assertNull(row.get("active_code"));
        var envelope = new ProtectedDocumentNumber((Integer) row.get("number_key_version"),
                (String) row.get("number_nonce"), (String) row.get("number_ciphertext"), (String) row.get("number_hash"));
        assertEquals(document.number(), envelope);
        assertEquals(new DocumentNumber(FAKE_NUMBER), crypto.reveal(envelope,
                new DocumentProtectionContext(owner, type, document.code())));
        assertTrue(readRows(document.code(), "TK-ZZZZZZ").isEmpty());
    }

    /** Nộp lần nữa chỉ thay bản PENDING, giữ đủ ảnh/lịch sử của bản cũ. */
    @Test void supersedesOnlyPendingAndMovesLatest() {
        String owner = accountCode(newAccount());
        var old = fixture(owner, DocumentType.IDENTITY_CARD);
        var current = fixture(owner, DocumentType.IDENTITY_CARD);
        assertTrue(writes.insert(old)); assertTrue(writes.insert(current));
        assertEquals("SUPERSEDED", read(old).get("status"));
        assertEquals(2L, read(old).get("image_count"));
        assertEquals("PENDING", read(current).get("status"));
        assertEquals(current.code(), read(old).get("latest_code"));
    }

    /** Bản đang dùng và lịch sử VERIFIED/REJECTED không bị nộp mới thay trạng thái hoặc con trỏ active. */
    @Test void preservesVerifiedActiveAndRejectedHistory() {
        long ownerId = newAccount(); String owner = accountCode(ownerId);
        var verifiedValues = submission(ownerId, "IDENTITY_CARD", "VERIFIED");
        long verified = insert("submission", verifiedValues);
        insert("head", head(ownerId, "IDENTITY_CARD", verified, verified));
        var rejected = fixture(owner, DocumentType.IDENTITY_CARD);
        assertTrue(writes.insert(rejected));
        SensitiveSql.execute(() -> jdbc.update(documentSql("update_review"), new MapSqlParameterSource()
                .addValue("id", read(rejected).get("id")).addValue("status", "REJECTED")
                .addValue("reviewedAt", AT).addValue("reviewerId", ownerId)
                .addValue("rejectionCode", "ILLEGIBLE").addValue("rejectionNote", null)));
        var current = fixture(owner, DocumentType.IDENTITY_CARD);
        assertTrue(writes.insert(current));
        assertEquals("REJECTED", read(rejected).get("status"));
        assertEquals(verifiedValues.getValue("code"), read(current).get("active_code"));
        assertEquals("VERIFIED", readRows((String) verifiedValues.getValue("code"), owner).getFirst().get("status"));
    }

    /** Trùng GT hoàn tác supersede trong savepoint; cùng transaction vẫn thử GT khác được. */
    @Test void codeCollisionRestoresPreviousPendingAndAllowsRetry() {
        String owner = accountCode(newAccount());
        var old = fixture(owner, DocumentType.IDENTITY_CARD);
        assertTrue(writes.insert(old));
        var next = fixture(owner, DocumentType.IDENTITY_CARD);
        var collision = withCodeAndImages(next, old.code(), next.frontImage(), next.backImage());
        assertFalse(writes.insert(collision));
        assertEquals("PENDING", read(old).get("status"));
        assertEquals(old.code(), read(old).get("latest_code"));
        assertEquals(2L, read(old).get("image_count"));
        assertTrue(writes.insert(next));
        assertEquals("SUPERSEDED", read(old).get("status"));
    }

    /** AN dùng lại trả đúng 409 nghiệp vụ; lỗi không khiến PENDING cũ biến mất. */
    @Test void reusedUploadIsConflictNotCodeCollision() {
        String owner = accountCode(newAccount());
        var old = fixture(owner, DocumentType.IDENTITY_CARD);
        assertTrue(writes.insert(old));
        var next = withCodeAndImages(old, nextCode("GT"), freshSeal(old.frontImage()), freshSeal(old.backImage()));
        var error = assertThrows(DomainException.class, () -> writes.insert(next));
        assertEquals(ErrorCode.DOCUMENT_UPLOAD_ALREADY_USED, error.errorCode());
        assertEquals(DomainException.Category.CONFLICT, error.category());
        assertNull(error.getCause());
        assertTrue(readRows(next.code(), owner).isEmpty());
        assertEquals("PENDING", read(old).get("status"));
        assertEquals(old.code(), read(old).get("latest_code"));
    }

    /** FK ảnh sau thất bại phải hoàn tác cả ảnh trước và bản mới, không chỉ dòng bị lỗi. */
    @Test void invalidSecondImageRollsBackAllWrites() {
        String owner = accountCode(newAccount());
        var old = fixture(owner, DocumentType.IDENTITY_CARD); assertTrue(writes.insert(old));
        var next = fixture(owner, DocumentType.IDENTITY_CARD);
        var foreign = fixture(accountCode(newAccount()), DocumentType.IDENTITY_CARD);
        var invalid = withCodeAndImages(next, next.code(), next.frontImage(), foreign.backImage());
        var failure = assertThrows(SensitiveSqlFailure.class, () -> writes.insert(invalid));
        assertEquals("23503", failure.sqlState());
        assertEquals("fk_document_image_upload", failure.constraint());
        assertNull(failure.getCause());
        assertTrue(readRows(next.code(), owner).isEmpty());
        assertEquals("PENDING", read(old).get("status"));
        assertEquals(old.code(), read(old).get("latest_code"));
        // Ảnh trước của lần lỗi chưa bị tiêu thụ; sửa ảnh sau thì ghi được cùng GT.
        assertTrue(writes.insert(next));
    }

    /** Adapter không tự commit nếu giao dịch nghiệp vụ ngoài quyết định rollback. */
    @Test void rollsBackWithOuterTransaction() {
        var document = fixture(accountCode(newAccount()), DocumentType.DRIVER_LICENSE);
        var transaction = new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        transaction.executeWithoutResult(status -> { assertTrue(writes.insert(document)); status.setRollbackOnly(); });
        assertTrue(readRows(document.code(), document.accountCode()).isEmpty());
    }

    /** Fail-fast trước JDBC nếu use case quên transaction, tránh commit dở bản nộp/ảnh/head. */
    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void requiresCallerTransaction() {
        var document = fixture(accountCode(newAccount()), DocumentType.IDENTITY_CARD);
        assertThrows(IllegalStateException.class, () -> writes.insert(document));
        assertTrue(readRows(document.code(), document.accountCode()).isEmpty());
    }

    /** Hai connection cùng nộp ảnh khác nhau: cả hai hoàn tất, chỉ bản cuối PENDING và head trỏ đúng nó. */
    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentSubmissionsKeepOnePendingAndCorrectLatest() throws Exception {
        String owner = accountCode(newAccount());
        var a = fixture(owner, DocumentType.IDENTITY_CARD);
        var b = fixture(owner, DocumentType.IDENTITY_CARD);
        assertEquals(List.of(true, true), race(a, b));
        var rows = List.of(read(a), read(b));
        assertEquals(1L, rows.stream().filter(row -> "PENDING".equals(row.get("status"))).count());
        assertEquals(1L, rows.stream().filter(row -> "SUPERSEDED".equals(row.get("status"))).count());
        var pending = rows.stream().filter(row -> "PENDING".equals(row.get("status"))).findFirst().orElseThrow();
        assertEquals(pending.get("code"), pending.get("latest_code"));
        for (var row : rows) assertEquals(2L, row.get("image_count"));
    }

    /** Khoá head phải được lấy trước khi đụng PENDING; kiểm lúc transaction khác đang giữ đúng hàng đó. */
    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void waitsForHeadLockBeforeChangingPending() throws Exception {
        String owner = accountCode(newAccount());
        var old = fixture(owner, DocumentType.IDENTITY_CARD);
        new TransactionTemplate(transactions).executeWithoutResult(status -> assertTrue(writes.insert(old)));
        var next = fixture(owner, DocumentType.IDENTITY_CARD);
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var attemptedLock = new CountDownLatch(1); var reachedSupersede = new CountDownLatch(1);
        String lockSql = sqlLoader.load("sql/identity/lock_document_head.sql");
        String supersedeSql = sqlLoader.load("sql/identity/supersede_pending_document.sql");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holder = executor.submit(() -> {
                var transaction = new TransactionTemplate(transactions); transaction.setTimeout(15);
                transaction.executeWithoutResult(status -> {
                    // Khoá đối chứng độc lập, không thay theo SQL production trong phép thử đảo.
                    SensitiveSql.execute(() -> jdbc.queryForList(documentSql("lock_head_for_test"),
                            Map.of("accountCode", owner, "documentType", "IDENTITY_CARD"), Long.class));
                    locked.countDown(); await(release);
                });
            });
            try {
                assertTrue(locked.await(10, TimeUnit.SECONDS));
                doAnswer(invocation -> { attemptedLock.countDown(); return invocation.callRealMethod(); })
                        .when(observedJdbc).queryForList(eq(lockSql), any(SqlParameterSource.class), eq(Long.class));
                doAnswer(invocation -> { reachedSupersede.countDown(); return invocation.callRealMethod(); })
                        .when(observedJdbc).update(eq(supersedeSql), any(SqlParameterSource.class));
                var writer = executor.submit(() -> {
                    var transaction = new TransactionTemplate(transactions); transaction.setTimeout(15);
                    transaction.executeWithoutResult(status -> assertTrue(writes.insert(next)));
                });
                try {
                    assertTrue(attemptedLock.await(10, TimeUnit.SECONDS));
                    assertFalse(reachedSupersede.await(500, TimeUnit.MILLISECONDS),
                            "Submission reached a state change before acquiring the head lock.");
                } finally { release.countDown(); }
                writer.get(25, TimeUnit.SECONDS);
            } finally { release.countDown(); }
            holder.get(25, TimeUnit.SECONDS);
        }
        assertEquals(0L, reachedSupersede.getCount());
        assertEquals("SUPERSEDED", read(old).get("status"));
        assertEquals(next.code(), read(next).get("latest_code"));
    }

    /** Cùng AN dù đã qua kiểm trước: một commit, một đúng DOCUMENT_UPLOAD_ALREADY_USED, không mất bản thắng. */
    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentReuseCommitsOnlyOnce() throws Exception {
        String owner = accountCode(newAccount());
        var a = fixture(owner, DocumentType.IDENTITY_CARD);
        var b = withCodeAndImages(a, nextCode("GT"), freshSeal(a.frontImage()), freshSeal(a.backImage()));
        // Phép thử đảo CHỈ trên container test; bỏ comment, chạy riêng method này phải đỏ, rồi khôi phục.
        // jdbc.getJdbcTemplate().execute("ALTER TABLE identity.document_image DROP CONSTRAINT uq_document_image_upload");
        var result = race(a, b);
        assertEquals(1L, result.stream().filter(Boolean::booleanValue).count());
        var winner = result.getFirst() ? a : b;
        var loser = result.getFirst() ? b : a;
        assertEquals("PENDING", read(winner).get("status"));
        assertEquals(winner.code(), read(winner).get("latest_code"));
        assertEquals(2L, read(winner).get("image_count"));
        assertTrue(readRows(loser.code(), owner).isEmpty());
    }

    /** Cả JDBC TRACE và sql-log không in tên, phong bì hoặc key ở đường thành công/lỗi. */
    @Test void redactsSensitiveStatementsAndFailures() {
        var document = fixture(accountCode(newAccount()), DocumentType.IDENTITY_CARD);
        var root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var binds = (Logger) LoggerFactory.getLogger("org.springframework.jdbc.core.StatementCreatorUtils");
        Level previous = binds.getLevel();
        var logs = new ListAppender<ILoggingEvent>(); logs.setContext(root.getLoggerContext()); logs.start();
        root.addAppender(logs); binds.setLevel(Level.TRACE);
        try {
            assertTrue(writes.insert(document));
            var duplicateKey = new DocumentImage(nextCode("AN"), DocumentSide.BACK,
                    document.frontImage().sealedObjectKey(), "image/png", 100, 10, 10);
            // Dùng ảnh của tài khoản khác để gây lỗi row có dữ liệu, không chỉ lỗi do port tự ném.
            var foreign = fixture(accountCode(newAccount()), DocumentType.IDENTITY_CARD);
            var invalid = withCodeAndImages(document, nextCode("GT"), foreign.frontImage(), duplicateKey);
            var failure = assertThrows(SensitiveSqlFailure.class, () -> writes.insert(invalid));
            assertEquals("23503", failure.sqlState());
            assertEquals("fk_document_image_upload", failure.constraint());
            assertNull(failure.getCause());
            for (var event : logs.list) {
                String content = event.getFormattedMessage();
                if (event.getThrowableProxy() != null) content += ThrowableProxyUtil.asString(event.getThrowableProxy());
                for (String secret : List.of(document.fullName(), document.number().ciphertextBase64(),
                        document.number().nonceBase64(), document.number().lookupHash(),
                        document.frontImage().sealedObjectKey(), document.backImage().sealedObjectKey(),
                        foreign.frontImage().sealedObjectKey())) {
                    assertFalse(content.contains(secret), "Sensitive document data leaked to logs.");
                }
            }
            var p6 = logs.list.stream().filter(event -> event.getLoggerName().equals("p6spy")
                    && event.getFormattedMessage().contains("caller: identity.adapter.out.persistence.DocumentSubmissionWriteAdapter")).toList();
            if (environment.acceptsProfiles(Profiles.of("sql-log"))) {
                assertFalse(p6.isEmpty());
                for (var event : p6) assertTrue(event.getFormattedMessage().contains("[REDACTED sensitive SQL]"));
            } else assertTrue(p6.isEmpty());
        } finally {
            binds.setLevel(previous); root.detachAppender(logs); logs.stop();
        }
    }

    /** Dựng đủ hai lượt tải đúng chủ/loại bằng adapter thật; metadata ảnh chỉ là fixture không gọi MinIO. */
    private DocumentSubmission fixture(String owner, DocumentType type) {
        var front = imageFixture(owner, type, DocumentSide.FRONT);
        var back = imageFixture(owner, type, DocumentSide.BACK);
        String code = nextCode("GT");
        var protectedNumber = crypto.protect(new DocumentNumber(FAKE_NUMBER), new DocumentProtectionContext(owner, type, code));
        boolean card = type == DocumentType.IDENTITY_CARD;
        return new DocumentSubmission(code, owner, type, protectedNumber, card ? "LOCAL PRIVATE DOCUMENT" : null,
                card ? LocalDate.of(1990, 1, 1) : null, LocalDate.of(2035, 1, 1), card ? null : "B",
                card ? null : false, NOW, front, back);
    }

    /** AN thực sự có trong PostgreSQL; không giả danh chủ bằng cách chỉ truyền mã đúng định dạng. */
    private DocumentImage imageFixture(String owner, DocumentType type, DocumentSide side) {
        String code = nextCode("AN");
        assertTrue(uploads.insert(new DocumentUpload(code, owner, type, side, "incoming/" + UUID.randomUUID(), NOW, NOW.plusSeconds(300))));
        return new DocumentImage(code, side, "sealed/" + UUID.randomUUID(), "image/png", 100, 10, 10);
    }

    /** Bản nộp khác phải mã hoá lại với AAD của GT mới, không copy ciphertext sai ngữ cảnh. */
    private DocumentSubmission withCodeAndImages(DocumentSubmission source, String code, DocumentImage front, DocumentImage back) {
        var number = crypto.protect(new DocumentNumber(FAKE_NUMBER),
                new DocumentProtectionContext(source.accountCode(), source.documentType(), code));
        return new DocumentSubmission(code, source.accountCode(), source.documentType(), number, source.fullName(),
                source.birthDate(), source.expiresOn(), source.licenseClass(), source.automaticOnly(), source.submittedAt(), front, back);
    }

    /** Tái dùng AN nhưng key mới để test chỉ có lỗi uq_document_image_upload, không trùng sealed key. */
    private static DocumentImage freshSeal(DocumentImage source) {
        return new DocumentImage(source.uploadCode(), source.side(), "sealed/" + UUID.randomUUID(),
                source.contentType(), source.sizeBytes(), source.width(), source.height());
    }

    /** Truy vấn độc lập, không gọi lại logic ghi để tự chứng minh nó đúng. */
    private Map<String, Object> read(DocumentSubmission document) {
        var rows = readRows(document.code(), document.accountCode());
        assertEquals(1, rows.size()); return rows.getFirst();
    }

    /** Che cả truy vấn kiểm thử vì row chứa phong bì/PII, không log map khi assertion sai. */
    private List<Map<String, Object>> readRows(String code, String owner) {
        return SensitiveSql.execute(() -> jdbc.queryForList(documentSql("read_submission_persistence"),
                Map.of("code", code, "accountCode", owner)));
    }

    /** Hai transaction đã mở trước barrier; boolean true chỉ có sau commit. */
    private List<Boolean> race(DocumentSubmission a, DocumentSubmission b) throws Exception {
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> attempt(a, ready, start));
            var second = executor.submit(() -> attempt(b, ready, start));
            try { assertTrue(ready.await(10, TimeUnit.SECONDS)); }
            finally { start.countDown(); }
            return List.of(first.get(25, TimeUnit.SECONDS), second.get(25, TimeUnit.SECONDS));
        }
    }

    /** Chỉ 409 dùng lại AN là bên thua hợp lệ; timeout/deadlock/kết nối đều làm test lỗi. */
    private boolean attempt(DocumentSubmission document, CountDownLatch ready, CountDownLatch start) {
        var transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); transaction.setTimeout(15);
        try {
            transaction.executeWithoutResult(status -> {
                ready.countDown();
                try { if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Race start timed out."); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError("Race interrupted."); }
                assertTrue(writes.insert(document));
            });
            return true;
        } catch (DomainException failure) {
            assertEquals(ErrorCode.DOCUMENT_UPLOAD_ALREADY_USED, failure.errorCode());
            assertEquals(DomainException.Category.CONFLICT, failure.category());
            return false;
        }
    }

    /** Chờ có giới hạn; không để test treo nếu transaction đối ứng thất bại. */
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Transaction latch timed out."); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError("Transaction interrupted."); }
    }

    /** Mã fixture riêng, không dựa vào sequence CSDL local. */
    private static String nextCode(String prefix) { return "%s-P%05d".formatted(prefix, CODES.incrementAndGet()); }
}
