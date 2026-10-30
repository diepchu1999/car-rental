-- Chỉ đếm và đọc mã chủ; không trả hash/số giấy tờ cho assertion hay log.
SELECT a.code AS account_code
FROM identity.identity_card_registration r
JOIN identity.account a ON a.id = r.account_id
JOIN identity.document_submission d ON d.number_hash = r.number_hash
WHERE d.code = :code;
