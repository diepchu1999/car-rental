package com.carrental.identity.adapter.out.persistence;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.carrental.PostgresTestConfiguration;
import com.carrental.identity.application.command.RejectDocumentCommand;
import com.carrental.identity.application.port.out.WriteDocumentSubmissionPort;
import com.carrental.identity.application.port.out.WriteDocumentUploadPort;
import com.carrental.identity.domain.*;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.sql.SensitiveSql;
import com.carrental.shared.sql.SensitiveSqlFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** BR-130: quyết định từ chối dùng PostgreSQL thật, không mất bản VERIFIED hoặc ghi đè quyết định thắng. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@Transactional
class DocumentRejectionPersistenceIntegrationTest extends DocumentDatabaseTestSupport {
    private static final Instant REVIEWED_AT = Instant.parse(AT).plusSeconds(60);
    private static final String NOTE = "LOCAL PRIVATE REVIEW NOTE\nSecond line";
    private static final AtomicLong CODES = new AtomicLong();
    @Autowired WriteDocumentSubmissionPort writes;
    @Autowired WriteDocumentUploadPort uploads;
    @Autowired PlatformTransactionManager transactions;
    @Autowired Environment environment;

    /** Cả CCCD/GPLX đều giữ bản cũ VERIFIED, latest vẫn là bản vừa bị từ chối. */
    @ParameterizedTest @EnumSource(DocumentType.class)
    void rejectsLatestWhilePreservingActive(DocumentType type) {
        var fixture = pending(type, true);
        String reviewer = accountCode(newAccount());
        writes.reject(command(fixture.code(), DocumentRejectionReason.DATA_MISMATCH, NOTE), reviewer, REVIEWED_AT);
        var row = read(fixture.code());
        assertEquals("REJECTED", row.get("status"));
        assertEquals(REVIEWED_AT, ((Timestamp) row.get("reviewed_at")).toInstant());
        assertEquals(reviewer, row.get("reviewer_code"));
        assertEquals("DATA_MISMATCH", row.get("rejection_code"));
        assertEquals(NOTE, row.get("rejection_note"));
        assertEquals(fixture.code(), row.get("latest_code"));
        assertEquals(fixture.activeCode(), row.get("active_code"));
        assertEquals("VERIFIED", read(fixture.activeCode()).get("status"));
    }

    /** Sáu lý do được lưu nguyên mã; không ghi chú vẫn hợp lệ và không tự sinh active. */
    @ParameterizedTest @EnumSource(DocumentRejectionReason.class)
    void storesEveryReasonWithoutOptionalNote(DocumentRejectionReason reason) {
        var fixture = pending(DocumentType.IDENTITY_CARD, false);
        writes.reject(command(fixture.code(), reason, null), accountCode(newAccount()), REVIEWED_AT);
        var row = read(fixture.code());
        assertEquals(reason.name(), row.get("rejection_code"));
        assertNull(row.get("rejection_note"));
        assertNull(row.get("active_code"));
    }

    /** Mã không tồn tại là 404 nghiệp vụ, không bị lớp che JDBC biến thành lỗi nội bộ. */
    @Test void missingDocumentReturnsNotFound() {
        assertDomain(() -> writes.reject(command("GT-ZZZZZZ", DocumentRejectionReason.ILLEGIBLE, null),
                accountCode(newAccount()), REVIEWED_AT), ErrorCode.DOCUMENT_NOT_FOUND, DomainException.Category.NOT_FOUND);
    }

    /** Bản đã xử lý/bị thay không được đổi trạng thái, người duyệt hoặc ghi chú. */
    @ParameterizedTest @ValueSource(strings = {"VERIFIED", "REJECTED", "SUPERSEDED"})
    void refusesPreviouslyProcessedSubmission(String state) {
        long owner = newAccount();
        var values = submission(owner, "IDENTITY_CARD", state);
        long id = insert("submission", values);
        insert("head", head(owner, "IDENTITY_CARD", id, state.equals("VERIFIED") ? id : null));
        String code = (String) values.getValue("code");
        var before = read(code);
        assertConflict(() -> writes.reject(command(code, DocumentRejectionReason.EXPIRED, NOTE), accountCode(newAccount()), REVIEWED_AT));
        assertEquals(before, read(code));
    }

    /** PENDING không phải latest cũng bị chặn, kể cả dữ liệu lệch con trỏ do thao tác ngoài ứng dụng. */
    @Test void refusesPendingThatIsNotLatest() {
        long owner = newAccount();
        var old = submission(owner, "IDENTITY_CARD", "VERIFIED");
        long active = insert("submission", old);
        var pending = submission(owner, "IDENTITY_CARD", "PENDING");
        insert("submission", pending);
        insert("head", head(owner, "IDENTITY_CARD", active, active));
        String code = (String) pending.getValue("code");
        assertConflict(() -> writes.reject(command(code, DocumentRejectionReason.ILLEGIBLE, null), accountCode(newAccount()), REVIEWED_AT));
        assertEquals("PENDING", read(code).get("status"));
    }

    /** Lỗi CHECK thật được che, savepoint phục hồi transaction để thao tác hợp lệ sau vẫn chạy được. */
    @Test void constraintFailureRollsBackSavepointWithoutLeakingNote() {
        var fixture = pending(DocumentType.IDENTITY_CARD, true);
        String reviewer = accountCode(newAccount());
        var failure = assertThrows(SensitiveSqlFailure.class, () -> writes.reject(
                command(fixture.code(), DocumentRejectionReason.ILLEGIBLE, NOTE), reviewer, Instant.parse(AT).minusSeconds(1)));
        assertEquals("23514", failure.sqlState());
        assertEquals("chk_document_submission_review", failure.constraint());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains(NOTE));
        assertEquals("PENDING", read(fixture.code()).get("status"));
        writes.reject(command(fixture.code(), DocumentRejectionReason.ILLEGIBLE, null), reviewer, REVIEWED_AT);
        assertEquals("REJECTED", read(fixture.code()).get("status"));
        assertEquals(fixture.activeCode(), read(fixture.code()).get("active_code"));
    }

    /** Commit thuộc use case: rollback ngoài phải hoàn tác quyết định đã ghi thành công trong savepoint. */
    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void outerRollbackRestoresPending() {
        var fixture = transaction().execute(status -> pending(DocumentType.IDENTITY_CARD, true));
        String reviewer = transaction().execute(status -> accountCode(newAccount()));
        transaction().executeWithoutResult(status -> {
            writes.reject(command(fixture.code(), DocumentRejectionReason.ILLEGIBLE, null), reviewer, REVIEWED_AT);
            status.setRollbackOnly();
        });
        assertEquals("PENDING", read(fixture.code()).get("status"));
        assertEquals(fixture.activeCode(), read(fixture.code()).get("active_code"));
    }

    /** Port không tự mở giao dịch rồi commit thay cho use case. */
    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void requiresCallerTransaction() {
        assertThrows(IllegalStateException.class, () -> writes.reject(
                command("GT-ABC123", DocumentRejectionReason.ILLEGIBLE, null), "TK-ABC123", REVIEWED_AT));
    }

    /** Hai người cùng từ chối: đúng một commit, người thua 409; đọc lại khớp toàn bộ quyết định của bên thắng. */
    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentRejectionsHaveExactlyOneWinner() throws Exception {
        var fixture = transaction().execute(status -> pending(DocumentType.IDENTITY_CARD, true));
        String first = transaction().execute(status -> accountCode(newAccount()));
        String second = transaction().execute(status -> accountCode(newAccount()));
        var results = race(
                () -> writes.reject(command(fixture.code(), DocumentRejectionReason.ILLEGIBLE, "First note"), first, REVIEWED_AT),
                () -> writes.reject(command(fixture.code(), DocumentRejectionReason.EXPIRED, "Second note"), second, REVIEWED_AT.plusSeconds(1)));
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        boolean firstWon = results.getFirst();
        var row = read(fixture.code());
        assertEquals("REJECTED", row.get("status"));
        assertEquals(firstWon ? first : second, row.get("reviewer_code"));
        assertEquals(firstWon ? "ILLEGIBLE" : "EXPIRED", row.get("rejection_code"));
        assertEquals(firstWon ? "First note" : "Second note", row.get("rejection_note"));
        assertEquals(firstWon ? REVIEWED_AT : REVIEWED_AT.plusSeconds(1), ((Timestamp) row.get("reviewed_at")).toInstant());
        assertEquals(fixture.activeCode(), row.get("active_code"));
    }

    /** Nộp lại luôn thành công; từ chối trước thì giữ lịch sử REJECTED, sau thì 409 trên bản SUPERSEDED. */
    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rejectionAndResubmissionPreserveActiveAndLatest() throws Exception {
        var fixture = transaction().execute(status -> pending(DocumentType.IDENTITY_CARD, true));
        String reviewer = transaction().execute(status -> accountCode(newAccount()));
        var next = transaction().execute(status -> newSubmission(fixture.ownerCode()));
        var results = race(
                () -> writes.reject(command(fixture.code(), DocumentRejectionReason.ILLEGIBLE, NOTE), reviewer, REVIEWED_AT),
                () -> assertTrue(writes.insert(next)));
        assertTrue(results.get(1));
        var old = read(fixture.code());
        assertEquals(results.getFirst() ? "REJECTED" : "SUPERSEDED", old.get("status"));
        assertEquals(results.getFirst() ? reviewer : null, old.get("reviewer_code"));
        assertEquals(next.code(), old.get("latest_code"));
        assertEquals(fixture.activeCode(), old.get("active_code"));
        assertEquals("PENDING", read(next.code()).get("status"));
        assertEquals("VERIFIED", read(fixture.activeCode()).get("status"));
    }

    /** TRACE bind và profile sql-log đều không lộ GT/TK/ghi chú; phạm vi che được phục hồi sau lỗi. */
    @Test void redactsSqlAndConstraintErrors() {
        var fixture = pending(DocumentType.IDENTITY_CARD, false);
        String reviewer = accountCode(newAccount());
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Logger binds = (Logger) LoggerFactory.getLogger("org.springframework.jdbc.core.StatementCreatorUtils");
        Level previous = binds.getLevel();
        var logs = new ListAppender<ILoggingEvent>();
        logs.setContext(root.getLoggerContext()); logs.start(); root.addAppender(logs); binds.setLevel(Level.TRACE);
        try {
            assertThrows(SensitiveSqlFailure.class, () -> writes.reject(
                    command(fixture.code(), DocumentRejectionReason.ILLEGIBLE, NOTE), reviewer, Instant.parse(AT).minusSeconds(1)));
            writes.reject(command(fixture.code(), DocumentRejectionReason.ILLEGIBLE, NOTE), reviewer, REVIEWED_AT);
            assertFalse(SensitiveSql.isActive());
            for (var event : logs.list) {
                String text = event.getFormattedMessage();
                if (event.getThrowableProxy() != null) text += ThrowableProxyUtil.asString(event.getThrowableProxy());
                for (String secret : List.of(fixture.code(), reviewer, NOTE)) {
                    assertFalse(text.contains(secret), "Sensitive review data leaked.");
                }
            }
            var sqlEvents = logs.list.stream().filter(event -> event.getLoggerName().equals("p6spy")
                    && event.getFormattedMessage().contains("DocumentSubmissionWriteAdapter")).toList();
            if (environment.acceptsProfiles(Profiles.of("sql-log"))) {
                assertFalse(sqlEvents.isEmpty());
                assertTrue(sqlEvents.stream().allMatch(event -> event.getFormattedMessage().contains("[REDACTED sensitive SQL]")));
            } else assertTrue(sqlEvents.isEmpty());
        } finally { binds.setLevel(previous); root.detachAppender(logs); logs.stop(); }
    }

    /** Dựng chủ, head, bản đang chờ và tuỳ chọn bản VERIFIED lịch sử bằng SQL fixture riêng. */
    private Fixture pending(DocumentType type, boolean withActive) {
        long owner = newAccount(); Long active = null; String activeCode = null;
        if (withActive) {
            var verified = submission(owner, type.name(), "VERIFIED");
            active = insert("submission", verified); activeCode = (String) verified.getValue("code");
        }
        var pending = submission(owner, type.name(), "PENDING");
        long latest = insert("submission", pending);
        insert("head", head(owner, type.name(), latest, active));
        return new Fixture((String) pending.getValue("code"), accountCode(owner), activeCode);
    }

    /** Định danh fixture nội bộ, không chứa số giấy tờ hay khoá ảnh. */
    private record Fixture(String code, String ownerCode, String activeCode) { }

    /** Command thật, để test persistence không né validation đầu vào. */
    private static RejectDocumentCommand command(String code, DocumentRejectionReason reason, String note) {
        return new RejectDocumentCommand(code, reason, note);
    }

    /** Đọc độc lập bằng JDBC sau commit, không dùng kết quả trả về của adapter để tự chứng minh. */
    private Map<String, Object> read(String code) {
        return SensitiveSql.execute(() -> jdbc.queryForMap(documentSql("read_review_for_test"), Map.of("code", code)));
    }

    /** Mọi kết quả thua phải là đúng mã và category, không nuốt lỗi hạ tầng. */
    private static void assertDomain(Executable action, ErrorCode code, DomainException.Category category) {
        var failure = assertThrows(DomainException.class, action);
        assertEquals(code, failure.errorCode()); assertEquals(category, failure.category()); assertNull(failure.getCause());
    }

    /** Bản bị xử lý hoặc bị thay trả lỗi conflict, không trả thành công lặp lại. */
    private static void assertConflict(Executable action) {
        assertDomain(action, ErrorCode.DOCUMENT_REVIEW_CONFLICT, DomainException.Category.CONFLICT);
    }

    /** Giao dịch thật READ COMMITTED, timeout hữu hạn để test không treo khi sai thứ tự khoá. */
    private TransactionTemplate transaction() {
        var tx = new TransactionTemplate(transactions);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); tx.setTimeout(15);
        return tx;
    }

    /** Hai connection đã mở transaction trước khi cùng bắt đầu; future chỉ thành công sau commit. */
    private List<Boolean> race(Runnable first, Runnable second) throws Exception {
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> attempt(first, ready, start));
            var b = executor.submit(() -> attempt(second, ready, start));
            try { assertTrue(ready.await(10, TimeUnit.SECONDS)); } finally { start.countDown(); }
            return List.of(a.get(25, TimeUnit.SECONDS), b.get(25, TimeUnit.SECONDS));
        }
    }

    /** Không coi deadlock/timeout/lỗi kết nối là một kết quả thua nghiệp vụ hợp lệ. */
    private boolean attempt(Runnable action, CountDownLatch ready, CountDownLatch start) {
        try {
            transaction().executeWithoutResult(status -> {
                ready.countDown();
                try { if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Race start timed out."); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                action.run();
            });
            return true;
        } catch (DomainException failure) {
            assertEquals(ErrorCode.DOCUMENT_REVIEW_CONFLICT, failure.errorCode());
            assertEquals(DomainException.Category.CONFLICT, failure.category());
            return false;
        }
    }

    /** Bản nộp lại qua adapter insert thật, gồm hai lượt tải và hai ảnh hợp lệ. */
    private DocumentSubmission newSubmission(String owner) {
        var front = newImage(owner, DocumentSide.FRONT); var back = newImage(owner, DocumentSide.BACK);
        String code = nextCode("GT");
        var number = crypto.protect(new DocumentNumber(FAKE_NUMBER), new DocumentProtectionContext(owner, DocumentType.IDENTITY_CARD, code));
        return new DocumentSubmission(code, owner, DocumentType.IDENTITY_CARD, number, "LOCAL FAKE PERSON",
                LocalDate.of(1990, 1, 1), LocalDate.of(2035, 1, 1), null, null, REVIEWED_AT, front, back, null);
    }

    /** Metadata ảnh thử dùng khoá riêng; không gọi MinIO vì đây là cuộc đua transaction PostgreSQL. */
    private DocumentImage newImage(String owner, DocumentSide side) {
        String code = nextCode("AN");
        assertTrue(uploads.insert(new DocumentUpload(code, owner, DocumentType.IDENTITY_CARD, side,
                "incoming/" + UUID.randomUUID(), REVIEWED_AT, REVIEWED_AT.plusSeconds(300))));
        return new DocumentImage(code, side, "sealed/" + UUID.randomUUID(), "image/png", 100, 10, 10);
    }

    /** Mã riêng cho fixture này, không dùng sequence hoặc dữ liệu của local. */
    private static String nextCode(String prefix) { return "%s-R%05d".formatted(prefix, CODES.incrementAndGet()); }
}
