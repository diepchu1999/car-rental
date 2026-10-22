-- Sự kiện chỉ chứa HMAC của số/IP; các request bị giới hạn không sinh lần SENT.
INSERT INTO car_rental_otp.attempt_event(phone_key, ip_key, kind, occurred_at) VALUES (?, ?, ?, ?);
