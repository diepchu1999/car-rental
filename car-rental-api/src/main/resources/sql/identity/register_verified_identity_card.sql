-- BR-130: UNIQUE phân xử số giữa hai chủ, không kiểm còn trống bằng SELECT trước.
-- Cùng chủ được xác minh phiên bản mới; khác chủ trả 0, tuyệt đối không chuyển chủ số.
INSERT INTO identity.identity_card_registration AS registration (number_hash, account_id)
SELECT d.number_hash, d.account_id
FROM identity.document_submission d
JOIN identity.document_head h ON h.account_id = d.account_id AND h.document_type = d.document_type
WHERE d.code = :code AND d.document_type = 'IDENTITY_CARD'
  AND d.status = 'PENDING' AND h.latest_submission_id = d.id
ON CONFLICT ON CONSTRAINT uq_identity_card_registration_hash
DO UPDATE SET account_id = registration.account_id
WHERE registration.account_id = EXCLUDED.account_id;
