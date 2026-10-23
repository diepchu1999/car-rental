-- Fixture mô phỏng thao tác application trong cùng transaction, không phải endpoint xác minh.
UPDATE identity.document_head
SET latest_submission_id = :latestId, active_verified_submission_id = :activeId
WHERE account_id = :accountId AND document_type = :type;
