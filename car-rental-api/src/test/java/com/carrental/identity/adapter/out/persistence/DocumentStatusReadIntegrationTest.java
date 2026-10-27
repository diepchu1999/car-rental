package com.carrental.identity.adapter.out.persistence;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.carrental.PostgresTestConfiguration;
import com.carrental.identity.application.port.out.ReadDocumentSubmissionPort;
import com.carrental.identity.application.view.DocumentHeadSummary;
import com.carrental.identity.application.view.DocumentStatusDetail;
import com.carrental.identity.domain.DocumentSubmissionStatus;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** BR-130: đọc PostgreSQL thật, đúng chủ, đúng hai con trỏ và không lộ dữ liệu qua log. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@Transactional
class DocumentStatusReadIntegrationTest extends DocumentDatabaseTestSupport {
    @Autowired ReadDocumentSubmissionPort reads;
    @Autowired Environment environment;

    /** Cả hai loại và cả bốn trạng thái đều giữ đúng metadata, kể cả bản lịch sử SUPERSEDED. */
    @ParameterizedTest @EnumSource(DocumentSubmissionStatus.class)
    void readsAllPersistedStatuses(DocumentSubmissionStatus status) {
        long owner = newAccount();
        for (DocumentType type : DocumentType.values()) {
            var parameters = submission(owner, type.name(), status.name());
            insert("submission", parameters);
            var actual = reads.findStatusByCodeAndOwner(code(parameters), accountCode(owner)).orElseThrow();
            assertEquals(code(parameters), actual.code());
            assertEquals(type, actual.documentType());
            assertEquals(status, actual.status());
            assertEquals(LocalDate.of(2030, 1, 1), actual.expiresOn());
            assertEquals(Instant.parse(AT), actual.submittedAt());
            boolean reviewed = status == DocumentSubmissionStatus.VERIFIED || status == DocumentSubmissionStatus.REJECTED;
            assertEquals(reviewed ? Instant.parse(AT) : null, actual.reviewedAt());
            assertEquals(status == DocumentSubmissionStatus.REJECTED ? "ILLEGIBLE" : null, actual.rejectionCode());
            assertNull(actual.rejectionNote());
            // Kết quả reload dùng bởi POST vẫn giữ đúng hợp đồng năm trường hiện có.
            var reload = reads.findByCodeAndOwner(code(parameters), accountCode(owner)).orElseThrow();
            assertEquals(actual.code(), reload.code());
            assertEquals(actual.status(), reload.status());
            assertEquals(actual.expiresOn(), reload.expiresOn());
        }
    }

    /** Trả nguyên sáu mã lý do và ghi chú cho chủ để sửa hồ sơ, không thay thế bằng mã chung. */
    @ParameterizedTest
    @ValueSource(strings = {"ILLEGIBLE", "MISSING_SIDE", "DATA_MISMATCH", "EXPIRED", "HOLDER_MISMATCH", "SUSPECTED_FORGERY"})
    void returnsExactRejectionReasonAndOptionalNote(String reason) {
        long owner = newAccount();
        var parameters = submission(owner, "IDENTITY_CARD", "REJECTED")
                .addValue("rejectionCode", reason).addValue("rejectionNote", "LOCAL PRIVATE REVIEW NOTE");
        long id = insert("submission", parameters);
        insert("head", head(owner, "IDENTITY_CARD", id, null));
        var actual = reads.findStatusByCodeAndOwner(code(parameters), accountCode(owner)).orElseThrow();
        assertEquals(reason, actual.rejectionCode());
        assertEquals("LOCAL PRIVATE REVIEW NOTE", actual.rejectionNote());
        assertEquals(actual, reads.listHeadsByOwner(accountCode(owner)).getFirst().latestSubmission());
        assertEquals("DocumentStatusDetail[redacted]", actual.toString());
    }

    /** Có mã GT thật nhưng sai chủ phải rỗng giống mã không tồn tại; nền cho GET trả cùng 404. */
    @Test
    void hidesOtherOwnersStatus() {
        long owner = newAccount();
        String stranger = accountCode(newAccount());
        var parameters = submission(owner, "IDENTITY_CARD", "REJECTED");
        insert("submission", parameters);
        assertTrue(reads.findStatusByCodeAndOwner(code(parameters), accountCode(owner)).isPresent());
        assertTrue(reads.findStatusByCodeAndOwner(code(parameters), stranger).isEmpty());
        assertTrue(reads.findStatusByCodeAndOwner("GT-ZZZZZZ", accountCode(owner)).isEmpty());
        assertTrue(reads.findStatusByCodeAndOwner(code(parameters), "TK-ZZZZZZ").isEmpty());
    }

    /** Danh sách chỉ có head của chủ đang hỏi, không kéo dữ liệu của chủ khác vào response. */
    @Test
    void listsOnlyOwnersHeadsInStableOrder() {
        long owner = newAccount();
        long stranger = newAccount();
        var card = submission(owner, "IDENTITY_CARD", "PENDING");
        var license = submission(owner, "DRIVER_LICENSE", "REJECTED");
        insert("head", head(owner, "IDENTITY_CARD", insert("submission", card), null));
        insert("head", head(owner, "DRIVER_LICENSE", insert("submission", license), null));
        var foreign = submission(stranger, "IDENTITY_CARD", "VERIFIED");
        long foreignId = insert("submission", foreign);
        insert("head", head(stranger, "IDENTITY_CARD", foreignId, foreignId));

        var result = reads.listHeadsByOwner(accountCode(owner));
        assertEquals(List.of(DocumentType.DRIVER_LICENSE, DocumentType.IDENTITY_CARD),
                result.stream().map(DocumentHeadSummary::documentType).toList());
        assertEquals(List.of(code(license), code(card)),
                result.stream().map(item -> item.latestSubmission().code()).toList());
        assertTrue(result.stream().allMatch(item -> item.activeVerifiedSubmission() == null));
        assertEquals(List.of(code(foreign)), reads.listHeadsByOwner(accountCode(stranger)).stream()
                .map(item -> item.latestSubmission().code()).toList());
        assertThrows(UnsupportedOperationException.class, result::clear);
    }

    /** LEFT JOIN không được làm biến mất bản nộp đầu tiên chưa có VERIFIED. */
    @ParameterizedTest @EnumSource(DocumentType.class)
    void keepsFirstPendingWithoutActiveVersion(DocumentType type) {
        long owner = newAccount();
        var pending = submission(owner, type.name(), "PENDING");
        insert("head", head(owner, type.name(), insert("submission", pending), null));
        var result = reads.listHeadsByOwner(accountCode(owner));
        assertEquals(1, result.size());
        assertEquals(type, result.getFirst().documentType());
        assertEquals(code(pending), result.getFirst().latestSubmission().code());
        assertEquals(DocumentSubmissionStatus.PENDING, result.getFirst().latestSubmission().status());
        assertNull(result.getFirst().activeVerifiedSubmission());
    }

    /** Pending hoặc bị từ chối ở latest không che mất bản VERIFIED đã chọn, với cả hai loại. */
    @ParameterizedTest @ValueSource(strings = {"PENDING", "REJECTED"})
    void separatesLatestFromSelectedVerifiedVersion(String latestStatus) {
        long owner = newAccount();
        for (DocumentType type : DocumentType.values()) {
            var active = submission(owner, type.name(), "VERIFIED");
            long activeId = insert("submission", active);
            var latest = submission(owner, type.name(), latestStatus)
                    .addValue("submittedAt", "2026-10-14T03:00:00Z");
            if (latestStatus.equals("REJECTED")) latest.addValue("reviewedAt", "2026-10-14T04:00:00Z");
            insert("head", head(owner, type.name(), insert("submission", latest), activeId));
            var result = reads.listHeadsByOwner(accountCode(owner)).stream()
                    .filter(item -> item.documentType() == type).findFirst().orElseThrow();
            assertEquals(code(latest), result.latestSubmission().code());
            assertEquals(DocumentSubmissionStatus.valueOf(latestStatus), result.latestSubmission().status());
            assertEquals(code(active), result.activeVerifiedSubmission().code());
            assertEquals(DocumentSubmissionStatus.VERIFIED, result.activeVerifiedSubmission().status());
        }
    }

    /** Đọc đúng con trỏ dù hết hạn; không suy MAX(id), MAX(hạn) hay quay về một VERIFIED lịch sử. */
    @ParameterizedTest @EnumSource(DocumentType.class)
    void keepsSelectedExpiredVersionWithoutFallback(DocumentType type) {
        long owner = newAccount();
        var historical = submission(owner, type.name(), "VERIFIED").addValue("expiresOn", "2040-01-01");
        insert("submission", historical);
        var selected = submission(owner, type.name(), "VERIFIED").addValue("expiresOn", "2000-01-01");
        long selectedId = insert("submission", selected);
        var pending = submission(owner, type.name(), "PENDING");
        insert("head", head(owner, type.name(), insert("submission", pending), selectedId));
        var actual = reads.listHeadsByOwner(accountCode(owner)).getFirst();
        assertEquals(code(selected), actual.activeVerifiedSubmission().code());
        assertEquals(LocalDate.of(2000, 1, 1), actual.activeVerifiedSubmission().expiresOn());
        assertEquals(code(pending), actual.latestSubmission().code());
    }

    /** Cùng một bản đã duyệt có thể là cả latest và active, không tạo hai mục trùng loại. */
    @ParameterizedTest @EnumSource(DocumentType.class)
    void permitsSameLatestAndActive(DocumentType type) {
        long owner = newAccount();
        var verified = submission(owner, type.name(), "VERIFIED");
        long id = insert("submission", verified);
        insert("head", head(owner, type.name(), id, id));
        var result = reads.listHeadsByOwner(accountCode(owner));
        assertEquals(1, result.size());
        assertEquals(result.getFirst().latestSubmission(), result.getFirst().activeVerifiedSubmission());
        assertEquals(code(verified), result.getFirst().latestSubmission().code());
    }

    /** Chưa nộp hoặc head kỹ thuật chưa có latest không bị biến thành hồ sơ giả. */
    @Test
    void returnsEmptyWithoutSubmission() {
        long owner = newAccount();
        assertTrue(reads.listHeadsByOwner(accountCode(owner)).isEmpty());
        insert("head", head(owner, "IDENTITY_CARD", null, null));
        assertTrue(reads.listHeadsByOwner(accountCode(owner)).isEmpty());
        assertTrue(reads.listHeadsByOwner("TK-ZZZZZZ").isEmpty());
    }

    /** Thu log thật của cả hai truy vấn; profile sql-log vẫn che tham số và ghi chú, TRACE cũng vậy. */
    @Test
    void protectsStatusQueriesAndRestoresLoggingScope() {
        long owner = newAccount();
        String ownerCode = accountCode(owner);
        var parameters = submission(owner, "IDENTITY_CARD", "REJECTED")
                .addValue("rejectionNote", "LOCAL PRIVATE REVIEW NOTE");
        long id = insert("submission", parameters);
        insert("head", head(owner, "IDENTITY_CARD", id, null));
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Logger binds = (Logger) LoggerFactory.getLogger("org.springframework.jdbc.core.StatementCreatorUtils");
        Level previous = binds.getLevel();
        var logs = new ListAppender<ILoggingEvent>();
        logs.setContext(root.getLoggerContext());
        logs.start();
        root.addAppender(logs);
        binds.setLevel(Level.TRACE);
        try {
            var detail = reads.findStatusByCodeAndOwner(code(parameters), ownerCode).orElseThrow();
            var head = reads.listHeadsByOwner(ownerCode).getFirst();
            assertEquals("DocumentStatusDetail[redacted]", detail.toString());
            assertEquals("DocumentHeadSummary[redacted]", head.toString());
            assertFalse(SensitiveSql.isActive());
            for (var event : logs.list) {
                String text = event.getFormattedMessage();
                if (event.getThrowableProxy() != null) text += ThrowableProxyUtil.asString(event.getThrowableProxy());
                for (String value : List.of(code(parameters), ownerCode, "LOCAL PRIVATE REVIEW NOTE",
                        (String) parameters.getValue("ciphertext"), (String) parameters.getValue("hash"))) {
                    assertFalse(text.contains(value), "Sensitive document data leaked to logs.");
                }
            }
            var p6 = logs.list.stream().filter(event -> event.getLoggerName().equals("p6spy")).toList();
            if (environment.acceptsProfiles(Profiles.of("sql-log"))) {
                assertEquals(2, p6.size());
                assertTrue(p6.stream().allMatch(event -> event.getFormattedMessage().contains("[REDACTED sensitive SQL]")));
            } else assertTrue(p6.isEmpty());
        } finally {
            binds.setLevel(previous);
            root.detachAppender(logs);
            logs.stop();
        }
    }

    /** Khoá bộ trường tối thiểu để không vô tình đưa ID, số, tên, ngày sinh hay key ảnh vào view. */
    @Test
    void statusViewsExposeOnlyTrackingMetadata() {
        assertEquals(List.of("code", "documentType", "status", "expiresOn", "submittedAt", "reviewedAt",
                "rejectionCode", "rejectionNote"), Arrays.stream(DocumentStatusDetail.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList());
        assertEquals(List.of("documentType", "latestSubmission", "activeVerifiedSubmission"),
                Arrays.stream(DocumentHeadSummary.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName).toList());
    }

    /** Chỉ lấy mã fixture, không đưa tham số chứa dữ liệu riêng vào thông báo lỗi. */
    private static String code(MapSqlParameterSource parameters) { return (String) parameters.getValue("code"); }
}
