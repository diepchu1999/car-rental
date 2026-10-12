-- Giữ nguyên cách tách dòng và thứ tự đã lưu, không gộp lại khi đọc.
SELECT type, day_type, start_at, hours, daily_rate, uplift, amount
FROM pricing.quote_line WHERE quote_id = :quoteId ORDER BY line_number;
