-- Sau khi khoá head: chỉ xử lý bản mới nhất còn PENDING.
SELECT d.document_type
FROM identity.document_submission d
JOIN identity.document_head h ON h.account_id = d.account_id AND h.document_type = d.document_type
WHERE d.code = :code AND d.status = 'PENDING' AND h.latest_submission_id = d.id;
