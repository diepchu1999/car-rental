-- Không truyền active_verified_status: PostgreSQL tự sinh VERIFIED để FK kiểm trạng thái.
INSERT INTO identity.document_head (account_id, document_type, latest_submission_id, active_verified_submission_id)
VALUES (:accountId, :type, :latestId, :activeId)
RETURNING id;
