-- Cố đổi mức tăng sau khi đã lưu.
UPDATE booking.price_line SET day_type = 'HOLIDAY', uplift = 0.2 WHERE booking_id = :id;
