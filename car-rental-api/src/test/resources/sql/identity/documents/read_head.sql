-- Chỉ đọc metadata, không đọc số, bản mã hoặc object key vào kết quả nghiệm thu.
SELECT latest_submission_id, active_verified_submission_id, active_verified_status
FROM identity.document_head WHERE account_id = :accountId AND document_type = :type;
