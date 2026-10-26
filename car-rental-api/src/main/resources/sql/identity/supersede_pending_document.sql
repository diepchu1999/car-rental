-- Chỉ bản đang chờ bị thay; lịch sử VERIFIED/REJECTED không đổi.
UPDATE identity.document_submission
SET status = 'SUPERSEDED'
WHERE account_id = (SELECT id FROM identity.account WHERE code = :accountCode)
  AND document_type = :documentType AND status = 'PENDING';
