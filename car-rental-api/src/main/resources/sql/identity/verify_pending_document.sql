-- BR-130: FK chỉ cho CCCD thành VERIFIED khi số đã đăng ký đúng chủ trong cùng transaction.
UPDATE identity.document_submission d
SET status = 'VERIFIED', reviewed_at = :reviewedAt,
    reviewer_account_id = (SELECT id FROM identity.account WHERE code = :reviewerAccountCode)
FROM identity.document_head h
WHERE d.code = :code AND d.status = 'PENDING'
  AND h.account_id = d.account_id AND h.document_type = d.document_type
  AND h.latest_submission_id = d.id;
