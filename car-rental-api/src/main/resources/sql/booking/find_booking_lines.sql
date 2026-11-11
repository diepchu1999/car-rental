-- Không tính lại giá hoặc loại ngày từ cấu hình mới.
SELECT type, day_type, start_at, hours, daily_rate, uplift, amount
FROM booking.price_line WHERE booking_id = :bookingId ORDER BY line_number;
