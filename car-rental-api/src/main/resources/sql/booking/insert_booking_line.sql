-- ADR-0014: giữ đúng thứ tự và số tiền của từng dòng đã chốt.
INSERT INTO booking.price_line (booking_id, line_number, type, day_type, start_at, hours, daily_rate, uplift, amount)
VALUES (:bookingId, :lineNumber, :type, :dayType, :startAt, :hours, :dailyRate, :uplift, :amount);
