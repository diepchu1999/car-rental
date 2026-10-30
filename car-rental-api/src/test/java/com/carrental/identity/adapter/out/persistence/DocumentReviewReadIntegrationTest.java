package com.carrental.identity.adapter.out.persistence;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.carrental.PostgresTestConfiguration;
import com.carrental.identity.application.port.out.ReadDocumentSubmissionPort;
import com.carrental.identity.application.view.DocumentReviewDetail;
import com.carrental.identity.domain.DocumentProtectionContext;
import com.carrental.identity.domain.DocumentSide;
import com.carrental.identity.domain.DocumentSubmissionStatus;
import com.carrental.identity.domain.DocumentType;
import com.carrental.shared.sql.SensitiveSql;
import com.carrental.shared.sql.SensitiveSqlFailure;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** BR-130: đọc đúng phiên bản và hai ảnh từ PostgreSQL thật, phong bì giữ nguyên và không lộ log. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@Transactional
class DocumentReviewReadIntegrationTest extends DocumentDatabaseTestSupport {
    @Autowired ReadDocumentSubmissionPort reads;
    @Autowired Environment environment;

    /** Cả hai loại, bốn trạng thái và mọi metadata phải khớp hàng thật, không chỉ kiểm có kết quả. */
    @ParameterizedTest @EnumSource(DocumentSubmissionStatus.class)
    void readsBothTypesInEveryPersistedStatus(DocumentSubmissionStatus status) {
        for (DocumentType type : DocumentType.values()) {
            var fixture = create(newAccount(), type, status, "BOTH");
            var result = reads.findForReviewByCode(fixture.code()).orElseThrow();
            assertMaterial(fixture, result);
            assertEquals(status, result.submission().status());
            assertEquals(type, result.submission().documentType());
            assertEquals(Instant.parse(AT), result.submission().submittedAt());
            assertEquals(LocalDate.of(2030, 1, 1), result.submission().expiresOn());
            boolean reviewed = status == DocumentSubmissionStatus.VERIFIED || status == DocumentSubmissionStatus.REJECTED;
            assertEquals(reviewed ? Instant.parse(AT) : null, result.submission().reviewedAt());
            assertEquals(status == DocumentSubmissionStatus.REJECTED ? "ILLEGIBLE" : null, result.submission().rejectionCode());
            assertEquals(status == DocumentSubmissionStatus.REJECTED ? "LOCAL PRIVATE REVIEW NOTE" : null,
                    result.submission().rejectionNote());
            if (type == DocumentType.IDENTITY_CARD) {
                assertEquals("LOCAL FAKE DOCUMENT", result.fullName());
                assertEquals(LocalDate.of(1990, 1, 1), result.birthDate());
                assertNull(result.licenseClass()); assertNull(result.automaticOnly());
            } else {
                assertNull(result.fullName()); assertNull(result.birthDate());
                assertEquals("B", result.licenseClass()); assertEquals(true, result.automaticOnly());
            }
        }
    }

    /** Mã GT chọn đúng lịch sử dù head đã trỏ bản khác; không trộn số hoặc ảnh của tài khoản khác. */
    @Test void readsExactCodeRatherThanLatestOrAnotherOwner() {
        long owner = newAccount();
        var old = create(owner, DocumentType.IDENTITY_CARD, DocumentSubmissionStatus.SUPERSEDED, "BOTH");
        var latest = create(owner, DocumentType.IDENTITY_CARD, DocumentSubmissionStatus.PENDING, "BOTH");
        insert("head", head(owner, "IDENTITY_CARD", latest.id(), null));
        var stranger = create(newAccount(), DocumentType.IDENTITY_CARD, DocumentSubmissionStatus.PENDING, "BOTH");
        for (var fixture : List.of(old, latest, stranger)) {
            assertMaterial(fixture, reads.findForReviewByCode(fixture.code()).orElseThrow());
        }
        assertEquals(DocumentSubmissionStatus.SUPERSEDED, reads.findForReviewByCode(old.code()).orElseThrow().submission().status());
    }

    /** Không tồn tại trả rỗng; chuỗi giống SQL chỉ là giá trị bind, không mở rộng tập kết quả. */
    @Test void missingCodeAndSqlTextReturnEmpty() {
        create(newAccount(), DocumentType.IDENTITY_CARD, DocumentSubmissionStatus.PENDING, "BOTH");
        assertTrue(reads.findForReviewByCode("GT-ZZZZZZ").isEmpty());
        assertTrue(reads.findForReviewByCode("GT-ZZZZZZ' OR '1'='1").isEmpty());
    }

    /** Thiếu một hoặc cả hai ảnh là lỗi dữ liệu; không được biến mất bởi INNER JOIN hoặc trả hồ sơ dở. */
    @ParameterizedTest @ValueSource(strings = {"NONE", "FRONT", "BACK"})
    void incompleteImagesFailClosed(String images) {
        var fixture = create(newAccount(), DocumentType.IDENTITY_CARD, DocumentSubmissionStatus.PENDING, images);
        var failure = assertThrows(SensitiveSqlFailure.class, () -> reads.findForReviewByCode(fixture.code()));
        assertNull(failure.getCause()); assertEquals(0, failure.getSuppressed().length);
        assertFalse(failure.getMessage().contains(fixture.code()));
        assertFalse(failure.getMessage().contains("LOCAL FAKE DOCUMENT"));
        assertFalse(SensitiveSql.isActive());
        // Lỗi đọc không được nuốt bản ghi hoặc thay đổi đường khách đọc trạng thái.
        assertTrue(reads.findStatusByCodeAndOwner(fixture.code(), fixture.ownerCode()).isPresent());
    }

    /** P6Spy và TRACE JDBC không lộ tham số, phong bì, tên/ngày sinh hoặc key; scope được dọn sau đọc. */
    @Test void redactsSensitiveReadWithAndWithoutSqlLogging() {
        var fixture = create(newAccount(), DocumentType.IDENTITY_CARD, DocumentSubmissionStatus.REJECTED, "BOTH");
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Logger binds = (Logger) LoggerFactory.getLogger("org.springframework.jdbc.core.StatementCreatorUtils");
        Level previous = binds.getLevel();
        var logs = new ListAppender<ILoggingEvent>();
        logs.setContext(root.getLoggerContext()); logs.start(); root.addAppender(logs); binds.setLevel(Level.TRACE);
        try {
            var actual = reads.findForReviewByCode(fixture.code()).orElseThrow();
            assertEquals("DocumentReviewDetail[redacted]", actual.toString());
            assertFalse(SensitiveSql.isActive());
            var secrets = List.of(fixture.code(), fixture.ownerCode(), "LOCAL FAKE DOCUMENT", "1990-01-01",
                    "LOCAL PRIVATE REVIEW NOTE", fakeNumber(actual.accountCode()), actual.number().nonceBase64(),
                    actual.number().ciphertextBase64(), actual.number().lookupHash(),
                    actual.frontImage().sealedObjectKey(), actual.backImage().sealedObjectKey());
            for (var event : logs.list) {
                String text = event.getFormattedMessage();
                if (event.getThrowableProxy() != null) text += ThrowableProxyUtil.asString(event.getThrowableProxy());
                for (String secret : secrets) assertFalse(text.contains(secret), "Sensitive document data leaked to logs.");
            }
            var p6 = logs.list.stream().filter(e -> e.getLoggerName().equals("p6spy")).toList();
            if (environment.acceptsProfiles(Profiles.of("sql-log"))) {
                assertEquals(1, p6.size());
                assertTrue(p6.getFirst().getFormattedMessage().contains("[REDACTED sensitive SQL]"));
            } else assertTrue(p6.isEmpty());
        } finally { binds.setLevel(previous); root.detachAppender(logs); logs.stop(); }
    }

    /** Phong bì giữ đủ bốn trường và AAD; giải mã chỉ trong assertion, không phải adapter đọc. */
    private void assertMaterial(Fixture fixture, DocumentReviewDetail actual) {
        assertEquals(fixture.code(), actual.submission().code());
        assertEquals(fixture.ownerCode(), actual.accountCode());
        assertEquals(fixture.submission().getValue("keyVersion"), actual.number().keyVersion());
        assertEquals(fixture.submission().getValue("nonce"), actual.number().nonceBase64());
        assertEquals(fixture.submission().getValue("ciphertext"), actual.number().ciphertextBase64());
        assertEquals(fixture.submission().getValue("hash"), actual.number().lookupHash());
        var context = new DocumentProtectionContext(actual.accountCode(), actual.submission().documentType(), actual.submission().code());
        assertEquals(fakeNumber(actual.accountCode()), crypto.reveal(actual.number(), context).value());
        for (int i = 0; i < 2; i++) {
            var image = i == 0 ? actual.frontImage() : actual.backImage();
            var expected = fixture.images().get(i);
            assertEquals(i == 0 ? DocumentSide.FRONT : DocumentSide.BACK, image.side());
            assertEquals(fixture.uploadCodes().get(i), image.uploadCode());
            assertEquals(expected.getValue("objectKey"), image.sealedObjectKey());
            assertEquals(expected.getValue("contentType"), image.contentType());
            assertEquals(expected.getValue("sizeBytes"), image.sizeBytes());
            assertEquals(expected.getValue("width"), image.width());
            assertEquals(expected.getValue("height"), image.height());
        }
    }

    /** Tạo fixture riêng cho từng trường hợp; hai mặt cố ý khác loại/kích thước để bắt lỗi tráo JOIN. */
    private Fixture create(long owner, DocumentType type, DocumentSubmissionStatus status, String sides) {
        var parameters = submission(owner, type.name(), status.name());
        if (type == DocumentType.DRIVER_LICENSE) parameters.addValue("automaticOnly", true);
        if (status == DocumentSubmissionStatus.REJECTED) parameters.addValue("rejectionNote", "LOCAL PRIVATE REVIEW NOTE");
        long document = insert("submission", parameters);
        var images = new ArrayList<MapSqlParameterSource>();
        var codes = new ArrayList<String>();
        for (DocumentSide side : DocumentSide.values()) {
            if (!sides.equals("BOTH") && !sides.equals(side.name())) continue;
            var upload = upload(owner, type.name(), side.name());
            long uploadId = insert("upload", upload);
            var image = image(document, uploadId, owner, type.name(), side.name());
            if (side == DocumentSide.BACK) image.addValue("contentType", "image/jpeg")
                    .addValue("sizeBytes", 200L).addValue("width", 20).addValue("height", 30);
            insert("image", image); images.add(image); codes.add((String) upload.getValue("code"));
        }
        return new Fixture(document, (String) parameters.getValue("code"), accountCode(owner), parameters, images, codes);
    }

    /** Fixture chứa thông tin thử nhạy cảm, không để assertion tự in cả tham số SQL. */
    private record Fixture(long id, String code, String ownerCode, MapSqlParameterSource submission,
                           List<MapSqlParameterSource> images, List<String> uploadCodes) {
        /** Dữ liệu fixture chỉ đọc bằng accessor khi kiểm, không hiện ra log. */
        @Override public String toString() { return "Fixture[redacted]"; }
    }
}
