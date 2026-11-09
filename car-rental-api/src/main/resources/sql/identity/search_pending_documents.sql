-- BR-130: hàng chờ cấp công ty chỉ có metadata, không mở giấy tờ/ảnh và không nhận việc xác minh.
-- Thứ tự ổn định khi thời điểm bằng nhau; mã GT là khoá phụ, không dùng ID số ra ngoài.
SELECT s.code, a.code AS account_code, s.document_type, s.submitted_at
FROM identity.document_submission s
JOIN identity.account a ON a.id = s.account_id
WHERE s.status = 'PENDING'
ORDER BY s.submitted_at ASC, s.code COLLATE "C" ASC
LIMIT :fetchLimit OFFSET :offset;
