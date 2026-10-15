-- HMAC thay cho mã bản rõ; UUID chỉ là challenge kỹ thuật, không phải account/business code.
INSERT INTO car_rental_otp.challenge(challenge_id, scope_key, phone_key, code_digest, issued_at, expires_at,
                                   failed_attempts, state)
VALUES (?, ?, ?, ?, ?, ?, 0, 'ACTIVE');
