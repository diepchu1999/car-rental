package com.carrental.identity.adapter.out.persistence;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.carrental.PostgresTestConfiguration;
import com.carrental.identity.application.port.out.ReadDocumentUploadPort;
import com.carrental.identity.application.port.out.WriteDocumentUploadPort;
import com.carrental.identity.domain.DocumentSide;
import com.carrental.identity.domain.DocumentType;
import com.carrental.identity.domain.DocumentUpload;
import com.carrental.shared.logging.FailureSummary;
import com.carrental.shared.sql.SensitiveSql;
import com.carrental.shared.sql.SensitiveSqlFailure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BR-130: adapter thật, PostgreSQL thật, thu cả SQL log và lỗi; chỉ dùng dữ liệu giả trong container.
 * Chạy cả không profile và sql-log; PostgresTestConfiguration xác nhận datasource đúng mode thật.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@Transactional
class DocumentUploadPersistenceIntegrationTest extends DocumentDatabaseTestSupport {
    private static final AtomicLong CODES = new AtomicLong();
    @Autowired WriteDocumentUploadPort write;
    @Autowired ReadDocumentUploadPort read;
    @Autowired Environment environment;
    @Autowired PlatformTransactionManager transactions;
    private Logger root;
    private Logger binds;
    private Level previousBindLevel;
    private ListAppender<ILoggingEvent> logs;

    /** Bật TRACE đúng logger bind để chứng minh filter thật bảo vệ, không đổi P6Spy hoặc datasource. */
    @BeforeEach
    void captureLogsIncludingJdbcBindTrace() {
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        binds = (Logger) LoggerFactory.getLogger("org.springframework.jdbc.core.StatementCreatorUtils");
        previousBindLevel = binds.getLevel();
        binds.setLevel(Level.TRACE);
        logs = new ListAppender<>();
        logs.setContext(root.getLoggerContext());
        logs.start();
        root.addAppender(logs);
    }

    /** Không để mức TRACE hay appender test ảnh hưởng lớp khác. */
    @AfterEach
    void restoreLogging() {
        binds.setLevel(previousBindLevel);
        root.detachAppender(logs);
        logs.stop();
    }

    /** Cả CCCD/GPLX và hai mặt round-trip đúng; sai chủ và không có mã đều không lộ key. */
    @ParameterizedTest
    @EnumSource(DocumentType.class)
    void persistsBothSidesAndReadsOnlyForOwner(DocumentType type) {
        String owner = accountCode(newAccount());
        String stranger = accountCode(newAccount());
        for (DocumentSide side : DocumentSide.values()) {
            var upload = fixture(owner, type, side);
            logs.list.clear();
            assertTrue(write.insert(upload));
            assertEquals(upload, read.findByCodeAndOwner(upload.code(), owner).orElseThrow());
            assertTrue(read.findByCodeAndOwner(upload.code(), stranger).isEmpty());
            assertTrue(read.findByCodeAndOwner("AN-ZZZZZZ", owner).isEmpty());
            assertProtectedLog(upload);
        }
    }

    /** Trùng AN không ghi đè lượt cũ và không làm hỏng transaction để service có thể thử mã khác. */
    @Test
    void onlyCodeCollisionReturnsFalseAndLeavesOriginalUntouched() {
        String owner = accountCode(newAccount());
        var original = fixture(owner, DocumentType.IDENTITY_CARD, DocumentSide.FRONT);
        assertTrue(write.insert(original));
        var replacement = new DocumentUpload(original.code(), owner, DocumentType.DRIVER_LICENSE,
                DocumentSide.BACK, "incoming/" + UUID.randomUUID(), original.createdAt(), original.expiresAt());
        logs.list.clear();
        assertFalse(write.insert(replacement));
        assertEquals(original, read.findByCodeAndOwner(original.code(), owner).orElseThrow());
        assertTrue(write.insert(fixture(owner, DocumentType.DRIVER_LICENSE, DocumentSide.BACK)));
        assertProtectedLog(replacement);
    }

    /** UNIQUE object khác UNIQUE mã: phải lỗi đúng SQLSTATE/constraint, không lộ row trong stack. */
    @Test
    void duplicateObjectFailsWithSanitizedExactConstraint() {
        String owner = accountCode(newAccount());
        var original = fixture(owner, DocumentType.IDENTITY_CARD, DocumentSide.FRONT);
        assertTrue(write.insert(original));
        var duplicate = new DocumentUpload(nextCode(), owner, original.documentType(), original.side(),
                original.incomingObjectKey(), original.createdAt(), original.expiresAt());
        logs.list.clear();
        var failure = assertThrows(SensitiveSqlFailure.class, () -> write.insert(duplicate));
        assertEquals("23505", failure.sqlState());
        assertEquals("uq_document_upload_object", failure.constraint());
        assertSafeFailure(failure, original);
        assertProtectedLog(original);
        assertFalse(SensitiveSql.isActive());
    }

    /** Chủ thiếu không được trả false như thể trùng mã; không dùng INSERT SELECT âm thầm bỏ qua. */
    @Test
    void missingOwnerIsStorageFailureNotCodeCollision() {
        var upload = fixture("TK-ZZZZZZ", DocumentType.IDENTITY_CARD, DocumentSide.FRONT);
        logs.list.clear();
        var failure = assertThrows(SensitiveSqlFailure.class, () -> write.insert(upload));
        assertEquals("23502", failure.sqlState());
        assertSafeFailure(failure, upload);
        assertProtectedLog(upload);
    }

    /** Adapter tham gia transaction bên gọi, không tự commit một lượt tải khi caller rollback. */
    @Test
    void rollsBackWithCallerTransaction() {
        String owner = accountCode(newAccount());
        var upload = fixture(owner, DocumentType.DRIVER_LICENSE, DocumentSide.BACK);
        var nested = new TransactionTemplate(transactions);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        nested.executeWithoutResult(status -> {
            assertTrue(write.insert(upload));
            assertTrue(read.findByCodeAndOwner(upload.code(), owner).isPresent());
            status.setRollbackOnly();
        });
        assertTrue(read.findByCodeAndOwner(upload.code(), owner).isEmpty());
    }

    /** Sau truy vấn nhạy cảm, truy vấn thường vẫn giữ tham số/caller như trước khi bật sql-log. */
    @Test
    void restoresExpandedLoggingForFollowingOrdinaryQuery() {
        var upload = fixture(accountCode(newAccount()), DocumentType.IDENTITY_CARD, DocumentSide.FRONT);
        assertTrue(write.insert(upload));
        assertFalse(SensitiveSql.isActive());
        logs.list.clear();
        jdbc.queryForMap(sqlLoader.load("sql/shared/sql_logging_probe.sql"),
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                        .addValue("label", "ordinary-query-sentinel").addValue("quantity", 7)
                        .addValue("optionalValue", null, java.sql.Types.VARCHAR));
        var p6 = logs.list.stream().filter(event -> event.getLoggerName().equals("p6spy")).toList();
        if (environment.acceptsProfiles(Profiles.of("sql-log"))) {
            assertEquals(1, p6.size());
            assertTrue(p6.getFirst().getFormattedMessage().contains("'ordinary-query-sentinel'"));
            assertFalse(p6.getFirst().getFormattedMessage().contains("[REDACTED"));
        } else assertTrue(p6.isEmpty());
    }

    /** Kiểm tất cả sự kiện thu được, không chỉ message P6Spy; không in payload vào assertion khi lỗi. */
    private void assertProtectedLog(DocumentUpload upload) {
        for (var event : logs.list) {
            assertFalse(event.getFormattedMessage().contains(upload.incomingObjectKey()), "Object key leaked in a log event.");
            assertFalse(event.getFormattedMessage().contains(upload.code()), "Upload parameter leaked in a log event.");
            if (event.getThrowableProxy() != null) {
                assertFalse(ThrowableProxyUtil.asString(event.getThrowableProxy()).contains(upload.incomingObjectKey()),
                        "Object key leaked in a logged throwable.");
            }
        }
        var p6 = logs.list.stream().filter(event -> event.getLoggerName().equals("p6spy")).toList();
        if (environment.acceptsProfiles(Profiles.of("sql-log"))) {
            assertFalse(p6.isEmpty(), "Actual protected operations must still emit redacted P6Spy events.");
            for (var event : p6) {
                String message = event.getFormattedMessage();
                assertTrue(message.contains("[REDACTED sensitive SQL]"));
                assertTrue(message.contains("caller: identity.adapter.out.persistence.DocumentUpload"));
                assertTrue(message.matches("(?s)^SQL \\(\\d+ ms\\):\\n.*"));
            }
        } else assertTrue(p6.isEmpty(), "Default mode must bypass the datasource proxy.");
    }

    /** Dòng tóm tắt lỗi 500 và stack đã che nhưng vẫn giữ SQLSTATE/constraint cho chẩn đoán. */
    private static void assertSafeFailure(SensitiveSqlFailure failure, DocumentUpload upload) {
        var text = new StringWriter();
        failure.printStackTrace(new PrintWriter(text));
        String summary = FailureSummary.format(failure, "POST", "/test/documents");
        assertFalse(text.toString().contains(upload.incomingObjectKey()));
        assertFalse(summary.contains(upload.incomingObjectKey()));
        assertFalse(text.toString().contains(upload.code()));
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
    }

    /** TTL giả khác mặc định chứng minh adapter giữ mốc do application cung cấp, không tính lại. */
    private static DocumentUpload fixture(String owner, DocumentType type, DocumentSide side) {
        Instant at = Instant.parse("2026-10-23T03:00:00Z");
        return new DocumentUpload(nextCode(), owner, type, side, "incoming/" + UUID.randomUUID(),
                at, at.plusSeconds(47));
    }

    /** Mã fixture khác nhau, không dùng sequence hoặc tài khoản CSDL local. */
    private static String nextCode() { return "AN-U%05d".formatted(CODES.incrementAndGet()); }
}
