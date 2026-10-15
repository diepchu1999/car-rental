-- Scope sai không được lấy mã của phiên khác; khoá số đã giữ ở bước trước.
SELECT code_digest, expires_at, failed_attempts, state
FROM car_rental_otp.challenge WHERE challenge_id = ? AND scope_key = ? AND phone_key = ?;
