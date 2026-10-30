package com.carrental.identity.adapter.out.persistence;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.carrental.PostgresTestConfiguration;
import com.carrental.identity.application.command.RejectDocumentCommand;
import com.carrental.identity.application.command.VerifyDocumentCommand;
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

import java.sql.Timestamp;
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

/** BR-130: xác minh/claim/active cùng transaction, tranh số và tranh quyết định trên PostgreSQL thật. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@Transactional
class DocumentVerificationPersistenceIntegrationTest extends DocumentDatabaseTestSupport {
    private static final Instant REVIEWED_AT = Instant.parse(AT).plusSeconds(60);
    private static final AtomicLong CODES = new AtomicLong();
    @Autowired WriteDocumentSubmissionPort writes;
    @Autowired WriteDocumentUploadPort uploads;
    @Autowired PlatformTransactionManager transactions;
    @Autowired Environment environment;

    /** Hai loại đều chuyển active sang bản mới, giữ bản cũ VERIFIED; chỉ CCCD có đăng ký số. */
    @ParameterizedTest @EnumSource(DocumentType.class)
    void verifiesAndMovesActiveWithoutChangingHistory(DocumentType type) {
        var fixture = pending(type, true, null);
        String reviewer = accountCode(newAccount());
        verify(fixture.code(), reviewer);
        var row = read(fixture.code());
        assertEquals("VERIFIED", row.get("status"));
        assertEquals(reviewer, row.get("reviewer_code"));
        assertEquals(REVIEWED_AT, ((Timestamp) row.get("reviewed_at")).toInstant());
        assertNull(row.get("rejection_code")); assertNull(row.get("rejection_note"));
        assertEquals(fixture.code(), row.get("active_code")); assertEquals(fixture.code(), row.get("latest_code"));
        assertEquals("VERIFIED", read(fixture.activeCode()).get("status"));
        assertEquals(type == DocumentType.IDENTITY_CARD ? List.of(fixture.ownerCode()) : List.of(), registrations(fixture.code()));
    }

    /** Cùng chủ xác minh lại cùng số hợp lệ, UNIQUE không chặn việc gia hạn giấy tờ. */
    @Test void sameOwnerCanVerifyAnotherVersionOfSameNumber() {
        var fixture = pending(DocumentType.IDENTITY_CARD, true, null);
        assertEquals(List.of(fixture.ownerCode()), registrations(fixture.code()));
        verify(fixture.code(), accountCode(newAccount()));
        assertEquals(List.of(fixture.ownerCode()), registrations(fixture.code()));
        assertEquals(fixture.code(), read(fixture.code()).get("active_code"));
    }

    /** Không tự đặt quy tắc UNIQUE lên GPLX khi BR chỉ chốt CCCD. */
    @Test void differentAccountsMayVerifySameLicenseNumber() {
        String number = uniqueNumber();
        var first = pending(DocumentType.DRIVER_LICENSE, false, number);
        var second = pending(DocumentType.DRIVER_LICENSE, false, number);
        String reviewer = accountCode(newAccount());
        verify(first.code(), reviewer); verify(second.code(), reviewer);
        assertEquals("VERIFIED", read(first.code()).get("status"));
        assertEquals("VERIFIED", read(second.code()).get("status"));
        assertTrue(registrations(first.code()).isEmpty());
    }

    /** Chỉ nhập/chờ/từ chối không chiếm số; chủ khác vẫn có thể được xác minh cùng số đó. */
    @Test void pendingAndRejectedSubmissionsNeverReserveNumber() {
        String number = uniqueNumber();
        var first = pending(DocumentType.IDENTITY_CARD, false, number);
        var second = pending(DocumentType.IDENTITY_CARD, false, number);
        String reviewer = accountCode(newAccount());
        assertTrue(registrations(first.code()).isEmpty());
        writes.reject(new RejectDocumentCommand(first.code(), DocumentRejectionReason.ILLEGIBLE, null), reviewer, REVIEWED_AT);
        assertTrue(registrations(first.code()).isEmpty());
        verify(second.code(), reviewer);
        assertEquals(List.of(second.ownerCode()), registrations(first.code()));
    }

    /** Khác chủ bị 409; claim của bên thắng và pending/active của bên thua không đổi. */
    @Test void anotherOwnerCannotClaimVerifiedCard() {
        String number = uniqueNumber();
        var winner = pending(DocumentType.IDENTITY_CARD, false, number);
        var loser = pending(DocumentType.IDENTITY_CARD, true, number);
        String reviewer = accountCode(newAccount());
        verify(winner.code(), reviewer);
        assertDomain(() -> verify(loser.code(), reviewer), ErrorCode.IDENTITY_CARD_ALREADY_REGISTERED);
        assertEquals("PENDING", read(loser.code()).get("status"));
        assertEquals(loser.activeCode(), read(loser.code()).get("active_code"));
        assertEquals(List.of(winner.ownerCode()), registrations(loser.code()));
    }

    /** Không tự chuyển quyền sở hữu số cũ khi chủ đó đổi sang số khác hoặc bản cũ hết hạn. */
    @Test void replacingActiveDoesNotReleasePreviouslyVerifiedNumber() {
        var owner = pending(DocumentType.IDENTITY_CARD, true, uniqueNumber());
        String reviewer = accountCode(newAccount());
        verify(owner.code(), reviewer);
        var stranger = pending(DocumentType.IDENTITY_CARD, false, fakeNumber(owner.ownerCode()));
        assertDomain(() -> verify(stranger.code(), reviewer), ErrorCode.IDENTITY_CARD_ALREADY_REGISTERED);
        assertEquals(List.of(owner.ownerCode()), registrations(owner.activeCode()));
    }

    /** Đã xử lý/bị thay luôn conflict trước claim, không tạo thêm đăng ký số hay đổi người duyệt. */
    @ParameterizedTest @ValueSource(strings = {"VERIFIED", "REJECTED", "SUPERSEDED"})
    void refusesProcessedSubmission(String state) {
        long owner = newAccount();
        var values = submission(owner, "IDENTITY_CARD", state);
        long id = insert("submission", values);
        insert("head", head(owner, "IDENTITY_CARD", id, state.equals("VERIFIED") ? id : null));
        String code = (String) values.getValue("code"); var before = read(code);
        assertDomain(() -> verify(code, accountCode(newAccount())), ErrorCode.DOCUMENT_REVIEW_CONFLICT);
        assertEquals(before, read(code));
        assertEquals(state.equals("VERIFIED") ? List.of(accountCode(owner)) : List.of(), registrations(code));
    }

    /** Không có GT trả 404 đúng, không đổi thành lỗi 500 khi đi qua SensitiveSql. */
    @Test void missingSubmissionReturnsNotFound() {
        var failure = assertThrows(DomainException.class, () -> verify("GT-ZZZZZZ", accountCode(newAccount())));
        assertEquals(ErrorCode.DOCUMENT_NOT_FOUND, failure.errorCode());
        assertEquals(DomainException.Category.NOT_FOUND, failure.category());
    }

    /** CHECK thất bại sau khi nhận số phải hoàn tác cả claim, pending và active rồi dùng lại transaction được. */
    @Test void constraintFailureReleasesNewClaimAndKeepsPreviousActive() {
        var fixture = pending(DocumentType.IDENTITY_CARD, true, uniqueNumber());
        String reviewer = accountCode(newAccount());
        var failure = assertThrows(SensitiveSqlFailure.class, () -> writes.verify(VerifyDocumentCommand.from(fixture.code()),
                reviewer, Instant.parse(AT).minusSeconds(1)));
        assertEquals("23514", failure.sqlState()); assertEquals("chk_document_submission_review", failure.constraint());
        assertNull(failure.getCause()); assertTrue(registrations(fixture.code()).isEmpty());
        assertEquals("PENDING", read(fixture.code()).get("status"));
        assertEquals(fixture.activeCode(), read(fixture.code()).get("active_code"));
        verify(fixture.code(), reviewer);
        assertEquals(List.of(fixture.ownerCode()), registrations(fixture.code()));
    }

    /** Transaction ngoài rollback thì claim không tồn tại, bản pending và active cũ còn nguyên. */
    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void outerRollbackRestoresClaimAndHead() {
        var fixture = tx().execute(status -> pending(DocumentType.IDENTITY_CARD, true, uniqueNumber()));
        String reviewer = tx().execute(status -> accountCode(newAccount()));
        tx().executeWithoutResult(status -> { verify(fixture.code(), reviewer); status.setRollbackOnly(); });
        assertTrue(registrations(fixture.code()).isEmpty());
        assertEquals("PENDING", read(fixture.code()).get("status"));
        assertEquals(fixture.activeCode(), read(fixture.code()).get("active_code"));
    }

    /** Không mở transaction riêng thay use case. */
    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void requiresCallerTransaction() {
        assertThrows(IllegalStateException.class, () -> verify("GT-ABC123", "TK-ABC123"));
    }

    /** Hai tài khoản tranh cùng số: đúng một VERIFIED, một 409; đọc lại claim và hai head độc lập. */
    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentOwnersCannotVerifySameCard() throws Exception {
        String number = uniqueNumber();
        var first = tx().execute(status -> pending(DocumentType.IDENTITY_CARD, true, number));
        var second = tx().execute(status -> pending(DocumentType.IDENTITY_CARD, true, number));
        String reviewer = tx().execute(status -> accountCode(newAccount()));
        var results = race(() -> verify(first.code(), reviewer), () -> verify(second.code(), reviewer),
                ErrorCode.IDENTITY_CARD_ALREADY_REGISTERED);
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        var winner = results.getFirst() ? first : second; var loser = results.getFirst() ? second : first;
        assertEquals(List.of(winner.ownerCode()), registrations(winner.code()));
        assertEquals("VERIFIED", read(winner.code()).get("status"));
        assertEquals(winner.code(), read(winner.code()).get("active_code"));
        assertEquals("PENDING", read(loser.code()).get("status"));
        assertNull(read(loser.code()).get("reviewer_code"));
        assertEquals(loser.activeCode(), read(loser.code()).get("active_code"));
    }

    /** Hai người xác minh cùng bản: một thành công, một conflict; không ghi đè reviewer của bên thắng. */
    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentVerifiersHaveOneWinner() throws Exception {
        var fixture = tx().execute(status -> pending(DocumentType.IDENTITY_CARD, false, uniqueNumber()));
        String first = tx().execute(status -> accountCode(newAccount()));
        String second = tx().execute(status -> accountCode(newAccount()));
        var results = race(() -> verify(fixture.code(), first), () -> verify(fixture.code(), second), ErrorCode.DOCUMENT_REVIEW_CONFLICT);
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(results.getFirst() ? first : second, read(fixture.code()).get("reviewer_code"));
    }

    /** Xác minh và từ chối cùng bản: chỉ một quyết định; claim/active khớp bên thắng, không trạng thái nửa chừng. */
    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void verificationAndRejectionHaveOneWinner() throws Exception {
        var fixture = tx().execute(status -> pending(DocumentType.IDENTITY_CARD, true, uniqueNumber()));
        String verifier = tx().execute(status -> accountCode(newAccount()));
        String rejector = tx().execute(status -> accountCode(newAccount()));
        var results = race(() -> verify(fixture.code(), verifier),
                () -> writes.reject(new RejectDocumentCommand(fixture.code(), DocumentRejectionReason.ILLEGIBLE, null), rejector, REVIEWED_AT),
                ErrorCode.DOCUMENT_REVIEW_CONFLICT);
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        boolean verified = results.getFirst(); var row = read(fixture.code());
        assertEquals(verified ? "VERIFIED" : "REJECTED", row.get("status"));
        assertEquals(verified ? verifier : rejector, row.get("reviewer_code"));
        assertEquals(verified ? fixture.code() : fixture.activeCode(), row.get("active_code"));
        assertEquals(verified ? List.of(fixture.ownerCode()) : List.of(), registrations(fixture.code()));
    }

    /** Nộp lại luôn thành công; xác minh trước giữ bản vừa VERIFIED làm active, nộp trước thì verify conflict. */
    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void verificationAndResubmissionKeepConsistentHead() throws Exception {
        String number = uniqueNumber();
        var fixture = tx().execute(status -> pending(DocumentType.IDENTITY_CARD, true, number));
        String reviewer = tx().execute(status -> accountCode(newAccount()));
        var next = tx().execute(status -> newSubmission(fixture.ownerCode(), number));
        var results = race(() -> verify(fixture.code(), reviewer), () -> assertTrue(writes.insert(next)), ErrorCode.DOCUMENT_REVIEW_CONFLICT);
        assertTrue(results.get(1)); boolean verified = results.getFirst();
        var old = read(fixture.code());
        assertEquals(verified ? "VERIFIED" : "SUPERSEDED", old.get("status"));
        assertEquals(next.code(), old.get("latest_code"));
        assertEquals(verified ? fixture.code() : fixture.activeCode(), old.get("active_code"));
        assertEquals("PENDING", read(next.code()).get("status"));
        assertEquals(verified ? List.of(fixture.ownerCode()) : List.of(), registrations(fixture.code()));
    }

    /** Claim, trạng thái và lỗi CHECK không lộ hash/PII, kể cả TRACE và P6Spy thật. */
    @Test void redactsVerificationAndFailureLogs() {
        var fixture = pending(DocumentType.IDENTITY_CARD, true, uniqueNumber());
        String reviewer = accountCode(newAccount());
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Logger binds = (Logger) LoggerFactory.getLogger("org.springframework.jdbc.core.StatementCreatorUtils");
        Level previous = binds.getLevel(); var logs = new ListAppender<ILoggingEvent>();
        logs.setContext(root.getLoggerContext()); logs.start(); root.addAppender(logs); binds.setLevel(Level.TRACE);
        try {
            assertThrows(SensitiveSqlFailure.class, () -> writes.verify(VerifyDocumentCommand.from(fixture.code()), reviewer, Instant.parse(AT).minusSeconds(1)));
            verify(fixture.code(), reviewer); assertFalse(SensitiveSql.isActive());
            for (var event : logs.list) {
                String text = event.getFormattedMessage();
                if (event.getThrowableProxy() != null) text += ThrowableProxyUtil.asString(event.getThrowableProxy());
                for (String secret : List.of(fixture.code(), reviewer, fixture.hash())) assertFalse(text.contains(secret), "Sensitive verification data leaked.");
            }
            var sqlEvents = logs.list.stream().filter(event -> event.getLoggerName().equals("p6spy")
                    && event.getFormattedMessage().contains("DocumentSubmissionWriteAdapter")).toList();
            if (environment.acceptsProfiles(Profiles.of("sql-log"))) {
                assertFalse(sqlEvents.isEmpty());
                assertTrue(sqlEvents.stream().allMatch(event -> event.getFormattedMessage().contains("[REDACTED sensitive SQL]")));
            } else assertTrue(sqlEvents.isEmpty());
        } finally { binds.setLevel(previous); root.detachAppender(logs); logs.stop(); }
    }

    /** Dữ liệu thử riêng; bản cũ nếu có dùng số riêng của chủ, bản pending có thể tranh số khác. */
    private Fixture pending(DocumentType type, boolean active, String number) {
        long owner = newAccount(); String ownerCode = accountCode(owner); Long oldId = null; String oldCode = null;
        if (active) {
            var old = submission(owner, type.name(), "VERIFIED"); oldId = insert("submission", old); oldCode = (String) old.getValue("code");
        }
        var values = submission(owner, type.name(), "PENDING", number == null ? fakeNumber(ownerCode) : number);
        long id = insert("submission", values); insert("head", head(owner, type.name(), id, oldId));
        return new Fixture((String) values.getValue("code"), ownerCode, oldCode, (String) values.getValue("hash"));
    }

    /** Fixture giữ hash nội bộ để kiểm log, không in record theo mặc định. */
    private record Fixture(String code, String ownerCode, String activeCode, String hash) {
        /** Che cả hash của dữ liệu thử khi assertion thất bại. */
        @Override public String toString() { return "Fixture[redacted]"; }
    }

    /** Gọi adapter thật qua write port; test không giả bước nhận số. */
    private void verify(String code, String reviewer) { writes.verify(VerifyDocumentCommand.from(code), reviewer, REVIEWED_AT); }

    /** Đọc trạng thái và head độc lập, ngoài transaction thao tác ở các test đồng thời. */
    private Map<String, Object> read(String code) {
        return SensitiveSql.execute(() -> jdbc.queryForMap(documentSql("read_review_for_test"), Map.of("code", code)));
    }

    /** Không trả hash ra assertion; kiểm số lượng/chủ qua mã TK. */
    private List<String> registrations(String code) {
        return SensitiveSql.execute(() -> jdbc.queryForList(documentSql("read_registration_for_test"), Map.of("code", code), String.class));
    }

    /** Test âm bắt đúng lỗi nghiệp vụ và không giữ cause chứa dữ liệu JDBC. */
    private static void assertDomain(Executable action, ErrorCode code) {
        var failure = assertThrows(DomainException.class, action);
        assertEquals(code, failure.errorCode()); assertEquals(DomainException.Category.CONFLICT, failure.category()); assertNull(failure.getCause());
    }

    /** READ COMMITTED và giới hạn thời gian cho các connection thật. */
    private TransactionTemplate tx() {
        var tx = new TransactionTemplate(transactions); tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); tx.setTimeout(15); return tx;
    }

    /** Barrier sau khi mở hai transaction, kết quả thành công chỉ trả sau commit. */
    private List<Boolean> race(Runnable first, Runnable second, ErrorCode conflict) throws Exception {
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> attempt(first, conflict, ready, start));
            var b = executor.submit(() -> attempt(second, conflict, ready, start));
            try { assertTrue(ready.await(10, TimeUnit.SECONDS)); } finally { start.countDown(); }
            return List.of(a.get(25, TimeUnit.SECONDS), b.get(25, TimeUnit.SECONDS));
        }
    }

    /** Không đổi lỗi hạ tầng thành một kết quả thua hợp lệ. */
    private boolean attempt(Runnable action, ErrorCode conflict, CountDownLatch ready, CountDownLatch start) {
        try {
            tx().executeWithoutResult(status -> {
                ready.countDown();
                try { if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Race start timed out."); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                action.run();
            }); return true;
        } catch (DomainException failure) {
            assertEquals(conflict, failure.errorCode()); assertEquals(DomainException.Category.CONFLICT, failure.category()); return false;
        }
    }

    /** Bản nộp đầy đủ ảnh qua adapter insert thật, không giả một câu UPDATE trạng thái cho cuộc đua. */
    private DocumentSubmission newSubmission(String owner, String plaintext) {
        var front = newImage(owner, DocumentSide.FRONT); var back = newImage(owner, DocumentSide.BACK);
        String code = nextCode("GT"); var number = crypto.protect(new DocumentNumber(plaintext), new DocumentProtectionContext(owner, DocumentType.IDENTITY_CARD, code));
        return new DocumentSubmission(code, owner, DocumentType.IDENTITY_CARD, number, "LOCAL FAKE PERSON", LocalDate.of(1990, 1, 1),
                LocalDate.of(2035, 1, 1), null, null, REVIEWED_AT, front, back);
    }

    /** Metadata ảnh giả đúng shape, không gọi storage trong test persistence. */
    private DocumentImage newImage(String owner, DocumentSide side) {
        String code = nextCode("AN");
        assertTrue(uploads.insert(new DocumentUpload(code, owner, DocumentType.IDENTITY_CARD, side, "incoming/" + UUID.randomUUID(), REVIEWED_AT, REVIEWED_AT.plusSeconds(300))));
        return new DocumentImage(code, side, "sealed/" + UUID.randomUUID(), "image/png", 100, 10, 10);
    }

    /** Số thử riêng tránh dữ liệu của test có commit ảnh hưởng test khác. */
    private static String uniqueNumber() { return "LOCAL-CARD-" + UUID.randomUUID(); }

    /** Mã fixture riêng với các lớp test khác. */
    private static String nextCode(String prefix) { return "%s-V%05d".formatted(prefix, CODES.incrementAndGet()); }
}
