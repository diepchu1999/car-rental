-- Đọc độc lập quyết định và con trỏ; dữ liệu ghi chú vẫn phải nằm trong SensitiveSql.
SELECT d.status, d.reviewed_at, reviewer.code AS reviewer_code, d.rejection_code, d.rejection_note,
       latest.code AS latest_code, active.code AS active_code
FROM identity.document_submission d
LEFT JOIN identity.account reviewer ON reviewer.id = d.reviewer_account_id
LEFT JOIN identity.document_head h ON h.account_id = d.account_id AND h.document_type = d.document_type
LEFT JOIN identity.document_submission latest ON latest.id = h.latest_submission_id
LEFT JOIN identity.document_submission active ON active.id = h.active_verified_submission_id
WHERE d.code = :code;
