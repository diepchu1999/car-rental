-- BR-130: chỉ trùng mã AN mới được thử mã khác; không bỏ qua trùng khoá object.
INSERT INTO identity.document_upload (
    code, account_id, document_type, side, incoming_object_key, created_at, expires_at
) VALUES (
    :code, (SELECT id FROM identity.account WHERE code = :accountCode),
    :documentType, :side, :objectKey, :createdAt, :expiresAt
)
ON CONFLICT ON CONSTRAINT uq_document_upload_code DO NOTHING;
