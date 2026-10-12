-- Chỉ test: truyền trực tiếp hình dạng dòng sai xuống PostgreSQL, không qua domain.
INSERT INTO pricing.quote_line (quote_id, line_number, type, day_type, start_at, hours, daily_rate, uplift, amount)
VALUES (:quoteId, :lineNumber, :type, :dayType, CAST(:startAt AS TIMESTAMPTZ), :hours,
        :dailyRate, CAST(:uplift AS NUMERIC), :amount);
