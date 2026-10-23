-- Đọc độc lập sau commit của hai luồng, không suy kết quả từ futures.
SELECT COUNT(*) FROM identity.document_submission
WHERE account_id = :accountId AND document_type = :type AND status = 'PENDING';
