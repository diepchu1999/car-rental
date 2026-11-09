-- BR-130: tuyệt đối không đổi active_verified_submission_id khi khách nộp bản mới.
UPDATE identity.document_head
SET latest_submission_id = (SELECT id FROM identity.document_submission WHERE code = :code)
WHERE account_id = (SELECT id FROM identity.account WHERE code = :accountCode)
  AND document_type = :documentType;
