package com.carrental.identity.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/** BR-130: phá hoại bằng SQL thật, ép ngày cấp và cấm người xử lý trùng chủ ở cả INSERT/UPDATE. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@Transactional
class DocumentIssueDateConstraintIntegrationTest extends DocumentDatabaseTestSupport {
    /** Ngày cấp tương lai, sau hạn hoặc vô hạn đều phải bị đúng CHECK mới từ chối. */
    @ParameterizedTest @ValueSource(strings = {"2026-10-14", "2031-01-01", "infinity", "-infinity"})
    void rejectsInvalidIssueDates(String issuedOn) {
        var row = submission(newAccount(), "DRIVER_LICENSE", "PENDING").addValue("issuedOn", issuedOn);
        assertConstraint(() -> insert("submission", row), "23514", "document_submission", "chk_document_submission_issued_on");
    }

    /** Ngày cấp sau hạn vẫn bị chặn dù cả hai ngày đều trước ngày nộp. */
    @Test void rejectsIssueAfterExpiryIndependentlyOfSubmissionDate() {
        var row = submission(newAccount(), "DRIVER_LICENSE", "PENDING")
                .addValue("issuedOn", "2025-01-02").addValue("expiresOn", "2025-01-01");
        assertConstraint(() -> insert("submission", row), "23514", "document_submission", "chk_document_submission_issued_on");
    }

    /** CCCD không nhận trường chỉ dành cho GPLX. */
    @Test void rejectsIdentityCardIssueDate() {
        var row = submission(newAccount(), "IDENTITY_CARD", "PENDING").addValue("issuedOn", "2025-01-01");
        assertConstraint(() -> insert("submission", row), "23514", "document_submission", "chk_document_submission_issued_on");
    }

    /** 17:00 UTC đã là ngày kế tiếp ở Việt Nam; bằng ngày hết hạn cũng hợp lệ. */
    @Test void acceptsVietnamMidnightAndEqualExpiry() {
        var row = submission(newAccount(), "DRIVER_LICENSE", "PENDING")
                .addValue("submittedAt", "2026-01-01T17:00:00Z")
                .addValue("issuedOn", "2026-01-02").addValue("expiresOn", "2026-01-02");
        assertTrue(insert("submission", row) > 0);
    }

    /** Một giây trước mốc đổi ngày không được nhận ngày cấp của ngày hôm sau. */
    @Test void rejectsNextVietnamDateBeforeMidnight() {
        var row = submission(newAccount(), "DRIVER_LICENSE", "PENDING")
                .addValue("submittedAt", "2026-01-01T16:59:59Z").addValue("issuedOn", "2026-01-02");
        assertConstraint(() -> insert("submission", row), "23514", "document_submission", "chk_document_submission_issued_on");
    }

    /** V014 cho phép NULL để giữ lịch sử cũ; cửa nộp Java bắt buộc ngày cho bản mới. */
    @Test void permitsUnknownLegacyIssueDate() {
        assertTrue(insert("submission", submission(newAccount(), "DRIVER_LICENSE", "VERIFIED")
                .addValue("issuedOn", null)) > 0);
    }

    /** Không ai tự duyệt/từ chối kể cả bỏ qua use case và chèn thẳng một kết quả xử lý. */
    @ParameterizedTest @ValueSource(strings = {"VERIFIED", "REJECTED"})
    void rejectsSelfReviewOnInsert(String status) {
        long owner = newAccount();
        var row = submission(owner, "DRIVER_LICENSE", status).addValue("reviewerId", owner);
        assertConstraint(() -> insert("submission", row), "23514", "document_submission", "chk_document_submission_reviewer_not_owner");
    }

    /** CHECK cũng bảo vệ UPDATE trạng thái, không chỉ đường INSERT. */
    @ParameterizedTest @ValueSource(strings = {"VERIFIED", "REJECTED"})
    void rejectsSelfReviewOnUpdate(String status) {
        long owner = newAccount();
        long id = insert("submission", submission(owner, "DRIVER_LICENSE", "PENDING"));
        var row = submission(owner, "DRIVER_LICENSE", status).addValue("id", id).addValue("reviewerId", owner);
        assertConstraint(() -> jdbc.update(documentSql("update_review"), row), "23514",
                "document_submission", "chk_document_submission_reviewer_not_owner");
    }

    /** Người khác xử lý vẫn hợp lệ; NULL người xử lý của PENDING không bị CHECK bắt nhầm. */
    @ParameterizedTest @ValueSource(strings = {"VERIFIED", "REJECTED"})
    void permitsDifferentReviewer(String status) {
        long owner = newAccount();
        long id = insert("submission", submission(owner, "DRIVER_LICENSE", "PENDING"));
        var row = submission(owner, "DRIVER_LICENSE", status).addValue("id", id);
        assertNotEquals(owner, row.getValue("reviewerId"));
        assertEquals(1, jdbc.update(documentSql("update_review"), row));
    }
}
