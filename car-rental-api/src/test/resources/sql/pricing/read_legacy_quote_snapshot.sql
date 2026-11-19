-- Bỏ đúng cột vừa thêm để so sánh toàn bộ nội dung lịch sử trước/sau migration.
SELECT to_jsonb(q) - 'customer_account_code' AS snapshot FROM pricing.quote q WHERE code = :code;
