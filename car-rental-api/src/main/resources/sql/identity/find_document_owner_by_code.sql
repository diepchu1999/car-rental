-- BR-130: chủ bản nộp không thay đổi; use case dùng mã chủ để chặn tự xác minh.
SELECT a.code
FROM identity.document_submission d
JOIN identity.account a ON a.id = d.account_id
WHERE d.code = :code;
