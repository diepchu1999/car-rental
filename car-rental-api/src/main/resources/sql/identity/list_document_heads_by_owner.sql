-- BR-130: một snapshot cho latest và active, không chọn MAX hoặc lọc hạn để thay con trỏ đã chốt.
-- Chỉ head đã có bản nộp; LEFT JOIN giữ bản pending đầu tiên chưa có active.
SELECT h.document_type,
       latest.code AS latest_code, latest.document_type AS latest_document_type,
       latest.status AS latest_status, latest.expires_on AS latest_expires_on,
       latest.submitted_at AS latest_submitted_at, latest.reviewed_at AS latest_reviewed_at,
       latest.rejection_code AS latest_rejection_code, latest.rejection_note AS latest_rejection_note,
       active.code AS active_code, active.document_type AS active_document_type,
       active.status AS active_status, active.expires_on AS active_expires_on,
       active.submitted_at AS active_submitted_at, active.reviewed_at AS active_reviewed_at,
       active.rejection_code AS active_rejection_code, active.rejection_note AS active_rejection_note
FROM identity.document_head h
JOIN identity.account a ON a.id = h.account_id
JOIN identity.document_submission latest ON latest.id = h.latest_submission_id
LEFT JOIN identity.document_submission active ON active.id = h.active_verified_submission_id
WHERE a.code = :accountCode
ORDER BY h.document_type;
