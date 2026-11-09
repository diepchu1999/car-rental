-- BR-128/130: dữ liệu lịch sử không có ngày cấp được giữ NULL, không suy đoán hay backfill.
ALTER TABLE identity.document_submission ADD COLUMN issued_on DATE;

-- Ngày cấp chỉ thuộc GPLX. Ngày nộp là ngày Việt Nam, không phụ thuộc timezone của session SQL.
-- NULL chỉ phục vụ tương thích dữ liệu cũ; command/domain bắt buộc ngày cấp cho GPLX nộp mới.
ALTER TABLE identity.document_submission
    ADD CONSTRAINT chk_document_submission_issued_on CHECK (
        issued_on IS NULL OR (
            document_type = 'DRIVER_LICENSE'
            AND isfinite(issued_on)
            AND issued_on <= expires_on
            AND issued_on <= (submitted_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date
        )
    ),
    ADD CONSTRAINT chk_document_submission_reviewer_not_owner CHECK (
        reviewer_account_id IS NULL OR reviewer_account_id <> account_id
    );

COMMENT ON COLUMN identity.document_submission.issued_on IS
    'Driver license issue date selects its licensing regime; NULL legacy evidence requires resubmission for rental eligibility.';
