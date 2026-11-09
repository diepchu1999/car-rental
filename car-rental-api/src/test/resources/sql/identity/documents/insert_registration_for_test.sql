-- Test ràng buộc thực sự: không ON CONFLICT, không kiểm tồn tại trước.
INSERT INTO identity.identity_card_registration(number_hash, account_id) VALUES (:hash, :accountId);
