package com.carrental.identity.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import com.carrental.identity.domain.DocumentNumber;
import com.carrental.identity.domain.DocumentProtectionContext;
import com.carrental.identity.domain.DocumentType;
import com.carrental.identity.domain.ProtectedDocumentNumber;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** BR-130: ràng buộc V012 bằng app role trên PostgreSQL thật; mọi fixture trong lớp này rollback. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@Transactional
class DocumentConstraintIntegrationTest extends DocumentDatabaseTestSupport {
    /** Cả hai loại lưu phong bì đúng; chỉ giải mã bằng đúng tài khoản, loại và mã bản nộp. */
    @ParameterizedTest
    @ValueSource(strings = {"IDENTITY_CARD", "DRIVER_LICENSE"})
    void storesOnlyProtectedNumber(String type) {
        long owner = newAccount();
        var parameters = submission(owner, type, "PENDING");
        long id = insert("submission", parameters);
        var row = jdbc.queryForMap(documentSql("read_submission"), Map.of("id", id));
        assertFalse(row.containsValue(fakeNumber(accountCode(owner))));
        assertEquals(parameters.getValue("nonce"), row.get("number_nonce"));
        assertEquals(parameters.getValue("ciphertext"), row.get("number_ciphertext"));
        assertEquals(parameters.getValue("hash"), row.get("number_hash"));
        var stored = new ProtectedDocumentNumber((Integer) row.get("number_key_version"),
                (String) row.get("number_nonce"), (String) row.get("number_ciphertext"), (String) row.get("number_hash"));
        assertEquals(new DocumentNumber(fakeNumber(accountCode(owner))), crypto.reveal(stored,
                new DocumentProtectionContext(accountCode(owner), DocumentType.valueOf(type), (String) row.get("code"))));
    }

    /** Không tạo cột số rõ/URL ký hoặc BYTEA để giữ ảnh trong schema giấy tờ. */
    @Test
    void schemaDoesNotStorePlainNumbersSignedUrlsOrImageBytes() {
        var columns = jdbc.queryForList(documentSql("read_columns"), Map.of());
        assertEquals(Set.of("document_upload", "document_submission", "document_image", "document_head", "identity_card_registration"),
                columns.stream().map(row -> row.get("table_name")).collect(java.util.stream.Collectors.toSet()));
        for (var column : columns) {
            String name = (String) column.get("column_name");
            assertFalse(Set.of("number", "document_number", "identity_card_number", "license_number").contains(name));
            assertFalse(name.contains("url"));
            assertNotEquals("bytea", column.get("data_type"));
        }
    }

    /** UNIQUE có điều kiện ép một PENDING cho cùng chủ và loại. */
    @Test
    void rejectsSecondPendingSubmissionForTheSameType() {
        long owner = newAccount();
        insert("submission", submission(owner, "IDENTITY_CARD", "PENDING"));
        var duplicate = submission(owner, "IDENTITY_CARD", "PENDING");
        assertConstraint(() -> insert("submission", duplicate), "23505", "document_submission", "uq_document_submission_pending");
    }

    /** BR-130: nhập cùng số ở hai tài khoản không giữ chỗ CCCD; hai loại của một khách cũng độc lập. */
    @Test
    void permitsSameUnverifiedNumberAcrossAccountsAndIndependentTypes() {
        long first = newAccount();
        long second = newAccount();
        var a = submission(first, "IDENTITY_CARD", "PENDING", FAKE_NUMBER);
        var b = submission(second, "IDENTITY_CARD", "PENDING", FAKE_NUMBER);
        assertEquals(a.getValue("hash"), b.getValue("hash"));
        insert("submission", a);
        insert("submission", b);
        insert("submission", submission(first, "DRIVER_LICENSE", "PENDING"));
    }

    /** Các số đo/khoá/phong bì sai bị CHECK tương ứng chặn, không dựa vào validation Java. */
    @ParameterizedTest
    @CsvSource({"keyVersion,0,chk_document_number_key_version", "nonce,bad,chk_document_number_nonce",
            "ciphertext,bad,chk_document_number_ciphertext", "hash,bad,chk_document_number_hash",
            "expiresOn,infinity,chk_document_submission_expiry", "submittedAt,infinity,chk_document_submission_time",
            "code,GT-wrong,chk_document_submission_code", "status,UNKNOWN,chk_document_submission_status"})
    void rejectsMalformedSubmissionValues(String field, String value, String constraint) {
        var parameters = submission(newAccount(), "IDENTITY_CARD", "PENDING");
        parameters.addValue(field, field.equals("keyVersion") ? Integer.valueOf(value) : value);
        assertConstraint(() -> insert("submission", parameters), "23514", "document_submission", constraint);
    }

    /** Không cho null luồn qua CHECK; các trường chung bắt buộc có NOT NULL riêng. */
    @ParameterizedTest
    @CsvSource({"code,code", "accountId,account_id", "type,document_type", "status,status", "keyVersion,number_key_version",
            "nonce,number_nonce", "ciphertext,number_ciphertext", "hash,number_hash", "expiresOn,expires_on", "submittedAt,submitted_at"})
    void rejectsMissingSubmissionField(String field, String column) {
        var parameters = submission(newAccount(), "IDENTITY_CARD", "PENDING").addValue(field, null);
        assertNotNullColumn(() -> insert("submission", parameters), "document_submission", column);
    }

    /** CCCD/GPLX có bộ trường khác nhau; thiếu trường riêng không được CHECK chấp nhận dưới dạng NULL. */
    @ParameterizedTest
    @CsvSource({"IDENTITY_CARD,fullName", "IDENTITY_CARD,birthDate", "DRIVER_LICENSE,licenseClass", "DRIVER_LICENSE,automaticOnly"})
    void rejectsMissingTypeSpecificField(String type, String field) {
        var parameters = submission(newAccount(), type, "PENDING").addValue(field, null);
        assertConstraint(() -> insert("submission", parameters), "23514", "document_submission", "chk_document_submission_fields");
    }

    /** Sáu lý do đã chốt nhận được ghi chú hoặc thiếu ghi chú; không tự tạo mã lý do mới. */
    @ParameterizedTest
    @ValueSource(strings = {"ILLEGIBLE", "MISSING_SIDE", "DATA_MISMATCH", "EXPIRED", "HOLDER_MISMATCH", "SUSPECTED_FORGERY"})
    void acceptsApprovedRejectionReasons(String reason) {
        var parameters = submission(newAccount(), "IDENTITY_CARD", "REJECTED").addValue("rejectionCode", reason);
        long id = insert("submission", parameters);
        assertEquals(reason, jdbc.queryForMap(documentSql("read_submission"), Map.of("id", id)).get("rejection_code"));
    }

    /** Kết quả xử lý thiếu người/thời gian hoặc sai lý do phải bị đúng CHECK review từ chối. */
    @ParameterizedTest
    @CsvSource(value = {"VERIFIED,reviewerId,NULL", "VERIFIED,reviewedAt,NULL", "VERIFIED,reviewedAt,infinity",
            "REJECTED,rejectionCode,NULL", "REJECTED,rejectionCode,OTHER", "PENDING,rejectionCode,ILLEGIBLE"}, nullValues = "NULL")
    void rejectsInconsistentReview(String status, String field, String value) {
        var parameters = submission(newAccount(), "IDENTITY_CARD", status).addValue(field, value);
        assertConstraint(() -> insert("submission", parameters), "23514", "document_submission", "chk_document_submission_review");
    }

    /** Mô hình giữ active cũ khi nộp/từ chối bản mới; chỉ khi duyệt mới chuyển active và giữ lịch sử. */
    @Test
    void keepsLatestAndActiveVerifiedPointersSeparate() {
        long owner = newAccount();
        long original = insert("submission", submission(owner, "IDENTITY_CARD", "VERIFIED"));
        long pending = insert("submission", submission(owner, "IDENTITY_CARD", "PENDING"));
        insert("head", head(owner, "IDENTITY_CARD", pending, original));
        assertHead(owner, pending, original);
        var rejected = submission(owner, "IDENTITY_CARD", "REJECTED").addValue("id", pending);
        assertEquals(1, jdbc.update(documentSql("update_review"), rejected));
        assertHead(owner, pending, original);
        long replacement = insert("submission", submission(owner, "IDENTITY_CARD", "PENDING"));
        assertEquals(1, jdbc.update(documentSql("update_head"), head(owner, "IDENTITY_CARD", replacement, original)));
        assertHead(owner, replacement, original);
        var verified = submission(owner, "IDENTITY_CARD", "VERIFIED").addValue("id", replacement);
        assertEquals(1, jdbc.update(documentSql("update_review"), verified));
        assertEquals(1, jdbc.update(documentSql("update_head"), head(owner, "IDENTITY_CARD", replacement, replacement)));
        assertHead(owner, replacement, replacement);
        assertEquals("VERIFIED", jdbc.queryForMap(documentSql("read_submission"), Map.of("id", original)).get("status"));
        assertEquals("REJECTED", jdbc.queryForMap(documentSql("read_submission"), Map.of("id", pending)).get("status"));
    }

    /** Active không thể trỏ PENDING/REJECTED/SUPERSEDED dù FK id đơn thuần vẫn tìm thấy bản ghi. */
    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "REJECTED", "SUPERSEDED"})
    void rejectsUnverifiedActivePointer(String status) {
        long owner = newAccount();
        long id = insert("submission", submission(owner, "IDENTITY_CARD", status));
        assertConstraint(() -> insert("head", head(owner, "IDENTITY_CARD", id, id)),
                "23503", "document_head", "fk_document_head_active");
    }

    /** FK latest không được trỏ sang bản nộp của người khác hoặc loại khác. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsWrongLatestOwnerOrType(boolean wrongOwner) {
        long owner = newAccount();
        long id = insert("submission", submission(wrongOwner ? newAccount() : owner,
                wrongOwner ? "IDENTITY_CARD" : "DRIVER_LICENSE", "PENDING"));
        assertConstraint(() -> insert("head", head(owner, "IDENTITY_CARD", id, null)),
                "23503", "document_head", "fk_document_head_latest");
    }

    /** FK active phải kiểm chủ/loại ngay cả khi status VERIFIED đã đúng. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsWrongVerifiedOwnerOrType(boolean wrongOwner) {
        long owner = newAccount();
        long latest = insert("submission", submission(owner, "IDENTITY_CARD", "PENDING"));
        long foreign = insert("submission", submission(wrongOwner ? newAccount() : owner,
                wrongOwner ? "IDENTITY_CARD" : "DRIVER_LICENSE", "VERIFIED"));
        assertConstraint(() -> insert("head", head(owner, "IDENTITY_CARD", latest, foreign)),
                "23503", "document_head", "fk_document_head_active");
    }

    /** Hai lượt tải đúng hai mặt gắn được vào bản nộp, không cần bucket thật cho test ràng buộc SQL. */
    @Test
    void acceptsBothImagesWithCorrectOwnerTypeAndSide() {
        long owner = newAccount();
        long document = insert("submission", submission(owner, "IDENTITY_CARD", "PENDING"));
        for (String side : new String[]{"FRONT", "BACK"}) {
            long upload = insert("upload", upload(owner, "IDENTITY_CARD", side));
            assertTrue(insert("image", image(document, upload, owner, "IDENTITY_CARD", side)) > 0);
        }
    }

    /** Dùng lại upload ở một bản nộp khác bị UNIQUE chặn, không chỉ kiểm ở application. */
    @Test
    void rejectsReusingAnUploadInAnotherSubmission() {
        long owner = newAccount();
        long old = insert("submission", submission(owner, "IDENTITY_CARD", "SUPERSEDED"));
        long current = insert("submission", submission(owner, "IDENTITY_CARD", "PENDING"));
        long upload = insert("upload", upload(owner, "IDENTITY_CARD", "FRONT"));
        insert("image", image(old, upload, owner, "IDENTITY_CARD", "FRONT"));
        var reused = image(current, upload, owner, "IDENTITY_CARD", "FRONT");
        assertConstraint(() -> insert("image", reused), "23505", "document_image", "uq_document_image_upload");
    }

    /** FK ghép chặn lấy ảnh của khách khác, sai loại giấy tờ hoặc sai mặt. */
    @ParameterizedTest
    @ValueSource(strings = {"owner", "type", "side"})
    void rejectsForeignOrMismatchedUpload(String changed) {
        long owner = newAccount();
        long document = insert("submission", submission(owner, "IDENTITY_CARD", "PENDING"));
        long upload = insert("upload", upload(changed.equals("owner") ? newAccount() : owner,
                changed.equals("type") ? "DRIVER_LICENSE" : "IDENTITY_CARD", changed.equals("side") ? "BACK" : "FRONT"));
        var invalid = image(document, upload, owner, "IDENTITY_CARD", "FRONT");
        assertConstraint(() -> insert("image", invalid), "23503", "document_image", "fk_document_image_upload");
    }

    /** Một mặt chỉ có một ảnh trong cùng bản nộp, kể cả dùng hai upload khác nhau. */
    @Test
    void rejectsTwoImagesForOneSide() {
        long owner = newAccount();
        long document = insert("submission", submission(owner, "IDENTITY_CARD", "PENDING"));
        long first = insert("upload", upload(owner, "IDENTITY_CARD", "FRONT"));
        long second = insert("upload", upload(owner, "IDENTITY_CARD", "FRONT"));
        insert("image", image(document, first, owner, "IDENTITY_CARD", "FRONT"));
        var invalid = image(document, second, owner, "IDENTITY_CARD", "FRONT");
        assertConstraint(() -> insert("image", invalid), "23505", "document_image", "uq_document_image_side");
    }

    /** TTL không hợp lệ hoặc vô hạn bị chặn; không ép con số 5 phút vào migration. */
    @ParameterizedTest
    @ValueSource(strings = {"2026-10-13T03:00:00Z", "2026-10-13T02:59:59Z", "infinity"})
    void rejectsInvalidUploadLifetime(String expiry) {
        var invalid = upload(newAccount(), "IDENTITY_CARD", "FRONT").addValue("expiresAt", expiry);
        assertConstraint(() -> insert("upload", invalid), "23514", "document_upload", "chk_document_upload_time");
    }

    /** Đối chiếu trực tiếp hai con trỏ lưu trong CSDL, không suy từ lệnh UPDATE đã gọi. */
    private void assertHead(long owner, long latest, long active) {
        var stored = jdbc.queryForMap(documentSql("read_head"), Map.of("accountId", owner, "type", "IDENTITY_CARD"));
        assertEquals(latest, stored.get("latest_submission_id"));
        assertEquals(active, stored.get("active_verified_submission_id"));
        assertEquals("VERIFIED", stored.get("active_verified_status"));
    }
}
