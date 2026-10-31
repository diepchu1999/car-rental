package com.carrental.identity.adapter.out.persistence;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.carrental.PostgresTestConfiguration;
import com.carrental.identity.application.port.out.ReadDocumentSubmissionPort;
import com.carrental.identity.application.view.ActiveDocumentDetail;
import com.carrental.identity.domain.DocumentType;
import com.carrental.shared.sql.SensitiveSql;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** BR-130: truy vấn active qua adapter/PostgreSQL thật, không giả các hàng hay con trỏ head. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@Transactional
class ActiveDocumentReadIntegrationTest extends DocumentDatabaseTestSupport {
    @Autowired ReadDocumentSubmissionPort reads;
    @Autowired Environment environment;

    /** Một lần đọc trả đủ hai loại đúng thứ tự; GPLX giữ hạng/cờ, CCCD không bị biến null thành false. */
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void readsBothTypesWithExactEligibilityMetadata(boolean automaticOnly) {
        long owner = newAccount();
        var card = submission(owner, "IDENTITY_CARD", "VERIFIED").addValue("expiresOn", "2031-02-03");
        var license = submission(owner, "DRIVER_LICENSE", "VERIFIED").addValue("licenseClass", "D1")
                .addValue("automaticOnly", automaticOnly).addValue("expiresOn", "2032-04-05");
        active(owner, card); active(owner, license);
        var result = reads.listActiveByOwner(accountCode(owner));
        assertEquals(List.of(
                new ActiveDocumentDetail(code(license), DocumentType.DRIVER_LICENSE, LocalDate.of(2032, 4, 5), "D1", automaticOnly),
                new ActiveDocumentDetail(code(card), DocumentType.IDENTITY_CARD, LocalDate.of(2031, 2, 3), null, null)), result);
    }

    /** Bản mới PENDING/REJECTED không được che active cũ của cả CCCD/GPLX; phép thử đảo nhắm con trỏ này. */
    @ParameterizedTest @ValueSource(strings = {"PENDING", "REJECTED"})
    void pendingOrRejectedLatestDoesNotReplaceActive(String latestStatus) {
        long owner = newAccount();
        for (var type : DocumentType.values()) {
            var selected = submission(owner, type.name(), "VERIFIED").addValue("expiresOn", "2030-01-01");
            long selectedId = insert("submission", selected);
            var latest = submission(owner, type.name(), latestStatus).addValue("expiresOn", "2040-01-01")
                    .addValue("submittedAt", "2026-10-14T03:00:00Z");
            if (latestStatus.equals("REJECTED")) latest.addValue("reviewedAt", "2026-10-14T04:00:00Z");
            if (type == DocumentType.DRIVER_LICENSE) latest.addValue("licenseClass", "D").addValue("automaticOnly", true);
            insert("head", head(owner, type.name(), insert("submission", latest), selectedId));
            var selectedResult = reads.listActiveByOwner(accountCode(owner)).stream()
                    .filter(item -> item.documentType() == type).findFirst();
            assertTrue(selectedResult.isPresent(), "Pending/rejected latest must not hide the selected VERIFIED version.");
            var result = selectedResult.orElseThrow();
            assertEquals(code(selected), result.code()); assertEquals(LocalDate.of(2030, 1, 1), result.expiresOn());
            if (type == DocumentType.DRIVER_LICENSE) {
                assertEquals("B", result.licenseClass()); assertEquals(false, result.automaticOnly());
            }
        }
    }

    /** Chỉ có bản chưa xác minh thì không trả nó như active để cổng thuê hiểu nhầm là đủ điều kiện. */
    @ParameterizedTest @ValueSource(strings = {"PENDING", "REJECTED", "SUPERSEDED"})
    void unverifiedVersionsNeverBecomeActive(String status) {
        long owner = newAccount();
        for (var type : DocumentType.values()) {
            var latest = submission(owner, type.name(), status);
            insert("head", head(owner, type.name(), insert("submission", latest), null));
        }
        assertTrue(reads.listActiveByOwner(accountCode(owner)).isEmpty());
    }

    /** Không lọc theo CURRENT_DATE hoặc suy bản có hạn xa nhất/ID lớn nhất; giữ bản đã chọn dù hết hạn. */
    @ParameterizedTest @EnumSource(DocumentType.class)
    void expiredSelectionDoesNotFallBackToHistoricalVerified(DocumentType type) {
        long owner = newAccount();
        var selected = submission(owner, type.name(), "VERIFIED").addValue("expiresOn", "2000-01-01");
        long selectedId = insert("submission", selected);
        var historical = submission(owner, type.name(), "VERIFIED").addValue("expiresOn", "2040-01-01");
        insert("submission", historical);
        var latest = submission(owner, type.name(), "PENDING");
        insert("head", head(owner, type.name(), insert("submission", latest), selectedId));
        var result = reads.listActiveByOwner(accountCode(owner));
        assertEquals(1, result.size()); assertEquals(code(selected), result.getFirst().code());
        assertEquals(LocalDate.of(2000, 1, 1), result.getFirst().expiresOn());
    }

    /** Mã tài khoản là điều kiện bắt buộc: không lẫn chủ, không tự chọn chủ khi mã không tồn tại. */
    @Test void isolatesAccountsAndMissingOwner() {
        long owner = newAccount(); long stranger = newAccount();
        var mine = submission(owner, "DRIVER_LICENSE", "VERIFIED");
        var other = submission(stranger, "IDENTITY_CARD", "VERIFIED");
        active(owner, mine); active(stranger, other);
        assertEquals(List.of(code(mine)), reads.listActiveByOwner(accountCode(owner)).stream().map(ActiveDocumentDetail::code).toList());
        assertEquals(List.of(code(other)), reads.listActiveByOwner(accountCode(stranger)).stream().map(ActiveDocumentDetail::code).toList());
        assertTrue(reads.listActiveByOwner("TK-NONE00").isEmpty());
    }

    /** Cùng phiên bản latest/active chỉ trả một mục và danh sách không bị caller sửa. */
    @Test void returnsImmutableResultsForSameLatestAndActive() {
        long owner = newAccount(); var selected = submission(owner, "IDENTITY_CARD", "VERIFIED"); active(owner, selected);
        var result = reads.listActiveByOwner(accountCode(owner)); assertEquals(1, result.size());
        assertEquals(code(selected), result.getFirst().code()); assertThrows(UnsupportedOperationException.class, result::clear);
    }

    /** Không có head/active thì không tự quét lịch sử VERIFIED để dựng một hồ sơ đang dùng. */
    @Test void doesNotInferActiveWithoutSelection() {
        long owner = newAccount(); String ownerCode = accountCode(owner);
        assertTrue(reads.listActiveByOwner(ownerCode).isEmpty());
        var verified = submission(owner, "IDENTITY_CARD", "VERIFIED"); long id = insert("submission", verified);
        assertTrue(reads.listActiveByOwner(ownerCode).isEmpty());
        insert("head", head(owner, "IDENTITY_CARD", null, null));
        assertTrue(reads.listActiveByOwner(ownerCode).isEmpty());
        jdbc.update(documentSql("update_head"), head(owner, "IDENTITY_CARD", id, null));
        assertTrue(reads.listActiveByOwner(ownerCode).isEmpty());
    }

    /** Khi bước xác minh chuyển con trỏ, lần đọc sau dùng bản mới, không cache hoặc giữ hạn/hạng cũ. */
    @ParameterizedTest @EnumSource(DocumentType.class)
    void followsChangedActivePointer(DocumentType type) {
        long owner = newAccount(); var old = submission(owner, type.name(), "VERIFIED"); active(owner, old);
        assertEquals(code(old), reads.listActiveByOwner(accountCode(owner)).getFirst().code());
        var next = submission(owner, type.name(), "VERIFIED").addValue("expiresOn", "2035-06-07");
        if (type == DocumentType.DRIVER_LICENSE) next.addValue("licenseClass", "D").addValue("automaticOnly", true);
        long nextId = insert("submission", next);
        jdbc.update(documentSql("update_head"), head(owner, type.name(), nextId, nextId));
        var result = reads.listActiveByOwner(accountCode(owner)).getFirst();
        assertEquals(code(next), result.code()); assertEquals(LocalDate.of(2035, 6, 7), result.expiresOn());
        if (type == DocumentType.DRIVER_LICENSE) { assertEquals("D", result.licenseClass()); assertEquals(true, result.automaticOnly()); }
    }

    /** Hạng lạ vẫn phải đi tới application để báo CLASS_NOT_SUPPORTED, không biến thành chưa xác minh. */
    @Test void preservesUnknownClassForPolicyEvaluation() {
        long owner = newAccount(); var selected = submission(owner, "DRIVER_LICENSE", "VERIFIED").addValue("licenseClass", "LOCAL_UNKNOWN");
        active(owner, selected); assertEquals("LOCAL_UNKNOWN", reads.listActiveByOwner(accountCode(owner)).getFirst().licenseClass());
    }

    /** Khoá năm trường cần thiết; số, hash, tên, ngày sinh, ảnh, reviewer hoặc ghi chú không thuộc view. */
    @Test void exposesOnlyMinimalMetadataAndRedactsToString() {
        assertEquals(List.of("code", "documentType", "expiresOn", "licenseClass", "automaticOnly"),
                Arrays.stream(ActiveDocumentDetail.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList());
        var view = new ActiveDocumentDetail("GT-FAKE01", DocumentType.DRIVER_LICENSE, LocalDate.of(2030, 1, 1), "B", true);
        assertEquals("ActiveDocumentDetail[redacted]", view.toString());
    }

    /** SQL/TRACE phải che tham số; profile sql-log chỉ ghi đúng một SELECT cho cả hai loại, không mở ảnh. */
    @Test void redactsActiveReadAndRestoresLoggingScope() {
        long owner = newAccount(); String ownerCode = accountCode(owner);
        var card = submission(owner, "IDENTITY_CARD", "VERIFIED");
        var license = submission(owner, "DRIVER_LICENSE", "VERIFIED"); active(owner, card); active(owner, license);
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Logger binds = (Logger) LoggerFactory.getLogger("org.springframework.jdbc.core.StatementCreatorUtils");
        Level previous = binds.getLevel(); var logs = new ListAppender<ILoggingEvent>();
        logs.setContext(root.getLoggerContext()); logs.start(); root.addAppender(logs); binds.setLevel(Level.TRACE);
        try {
            assertEquals(2, reads.listActiveByOwner(ownerCode).size()); assertFalse(SensitiveSql.isActive());
            for (var event : logs.list) {
                String text = event.getFormattedMessage();
                if (event.getThrowableProxy() != null) text += ThrowableProxyUtil.asString(event.getThrowableProxy());
                for (String secret : List.of(ownerCode, code(card), code(license), fakeNumber(ownerCode),
                        (String) card.getValue("hash"), (String) card.getValue("ciphertext"), "LOCAL FAKE DOCUMENT")) {
                    assertFalse(text.contains(secret), "Sensitive document metadata leaked into logs.");
                }
            }
            var p6 = logs.list.stream().filter(e -> e.getLoggerName().equals("p6spy")).toList();
            if (environment.acceptsProfiles(Profiles.of("sql-log"))) {
                assertEquals(1, p6.size()); assertTrue(p6.getFirst().getFormattedMessage().contains("[REDACTED sensitive SQL]"));
            } else assertTrue(p6.isEmpty());
        } finally { binds.setLevel(previous); root.detachAppender(logs); logs.stop(); }
    }

    /** Fixture có cả latest và active cùng trỏ một bản đã xác minh, ràng buộc CSDL vẫn bật. */
    private void active(long owner, MapSqlParameterSource parameters) {
        long id = insert("submission", parameters);
        insert("head", head(owner, (String) parameters.getValue("type"), id, id));
    }

    /** Chỉ lấy mã fixture, không in cả bộ tham số chứa phong bì. */
    private static String code(MapSqlParameterSource parameters) { return (String) parameters.getValue("code"); }
}
