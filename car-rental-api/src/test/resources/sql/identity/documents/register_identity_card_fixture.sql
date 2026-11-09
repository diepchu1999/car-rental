-- Chỉ tạo fixture lịch sử VERIFIED cho các test đọc; không dùng để chứng minh luồng verify production.
INSERT INTO identity.identity_card_registration(number_hash, account_id)
VALUES (:hash, :accountId)
ON CONFLICT ON CONSTRAINT uq_identity_card_registration_hash DO NOTHING;
