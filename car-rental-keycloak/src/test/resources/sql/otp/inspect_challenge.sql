-- Đọc sau commit bằng connection độc lập, không dựa vào bộ nhớ của provider.
SELECT phone_key, code_digest, state, failed_attempts,
       EXTRACT(EPOCH FROM (expires_at - issued_at)) AS lifetime_seconds
FROM car_rental_otp.challenge WHERE challenge_id = ?;
