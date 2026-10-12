-- Dòng tiền đã tính, không đọc giá xe hoặc cấu hình từ schema khác.
INSERT INTO pricing.quote_line (
    quote_id, line_number, type, day_type, start_at, hours, daily_rate, uplift, amount
) VALUES (
    :quoteId, :lineNumber, :type, :dayType, :startAt, :hours, :dailyRate, :uplift, :amount
);
