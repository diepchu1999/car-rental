-- BR-130: chỉ từ chối bản đang chờ mới nhất; không sửa con trỏ active hoặc các bản VERIFIED cũ.
UPDATE identity.document_submission d
SET status = 'REJECTED',
    reviewed_at = :reviewedAt,
    reviewer_account_id = (SELECT id FROM identity.account WHERE code = :reviewerAccountCode),
    rejection_code = :rejectionCode,
    rejection_note = :rejectionNote
FROM identity.document_head h
WHERE d.code = :code AND d.status = 'PENDING'
  AND h.account_id = d.account_id AND h.document_type = d.document_type
  AND h.latest_submission_id = d.id;
