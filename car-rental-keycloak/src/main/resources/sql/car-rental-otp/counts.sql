-- BR-129: cửa sổ trượt, biên trái mở; mọi truy vấn đếm chạy dưới khoá số/IP.
SELECT count(*) FILTER (WHERE kind = 'SENT' AND phone_key = ? AND occurred_at > ?) AS phone_hour,
       count(*) FILTER (WHERE kind = 'SENT' AND phone_key = ? AND occurred_at > ?) AS phone_day,
       count(*) FILTER (WHERE kind = 'SENT' AND ip_key = ? AND occurred_at > ?) AS ip_sends,
       count(*) FILTER (WHERE kind = 'FAILED' AND ip_key = ? AND occurred_at > ?) AS ip_failures,
       max(occurred_at) FILTER (WHERE kind = 'SENT' AND phone_key = ?) AS last_sent
FROM car_rental_otp.attempt_event
WHERE phone_key = ? OR ip_key = ?;
