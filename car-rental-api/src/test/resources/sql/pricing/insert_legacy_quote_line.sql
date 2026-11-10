-- Dòng hợp lệ cho báo giá lịch sử của phép thử migration V015.
INSERT INTO pricing.quote_line (quote_id, line_number, type, day_type, start_at, hours, daily_rate, uplift, amount)
VALUES (:quoteId, 1, 'DAY_BLOCK', 'WEEKDAY', '2026-11-13T03:00:00Z', 24, 1000000, 0, 1000000);
