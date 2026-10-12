-- Giá trị tổng giữ nguyên vẫn không được UPDATE báo giá bất biến.
UPDATE pricing.quote SET total_amount = total_amount WHERE code = :code;
