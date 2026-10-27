-- BR-130: chỉ đọc thông tin cần cho kết quả nộp, luôn giới hạn chủ ngay trong SQL.
SELECT s.code, s.document_type, s.status, s.expires_on, s.submitted_at
FROM identity.document_submission s
JOIN identity.account a ON a.id = s.account_id
WHERE s.code = :code AND a.code = :accountCode;
