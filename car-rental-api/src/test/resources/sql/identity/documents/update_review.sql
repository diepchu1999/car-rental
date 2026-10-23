-- Chỉ dùng ở test mô hình dữ liệu; kiểm quyền và tranh chấp của use case thuộc phần xác minh.
UPDATE identity.document_submission
SET status = :status, reviewed_at = CAST(:reviewedAt AS TIMESTAMPTZ), reviewer_account_id = :reviewerId,
    rejection_code = :rejectionCode, rejection_note = :rejectionNote
WHERE id = :id;
