-- Giữ bất biến expires_at > issued_at khi mô phỏng OTP đã quá hạn.
UPDATE car_rental_otp.challenge
SET issued_at = clock_timestamp() - INTERVAL '6 minutes',
    expires_at = clock_timestamp() - INTERVAL '1 minute'
WHERE challenge_id = ?;
