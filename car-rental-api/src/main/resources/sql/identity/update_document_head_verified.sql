-- BR-130: chỉ đổi active sau khi bản latest đã VERIFIED; bản cũ vẫn giữ lịch sử.
UPDATE identity.document_head h
SET active_verified_submission_id = d.id
FROM identity.document_submission d
WHERE d.code = :code AND d.status = 'VERIFIED'
  AND h.account_id = d.account_id AND h.document_type = d.document_type
  AND h.latest_submission_id = d.id;
