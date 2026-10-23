-- Fixture giả cho V012; không dùng object hoặc tài khoản local.
INSERT INTO identity.document_upload
    (code, account_id, document_type, side, incoming_object_key, created_at, expires_at)
VALUES (:code, :accountId, :type, :side, :objectKey, CAST(:createdAt AS TIMESTAMPTZ), CAST(:expiresAt AS TIMESTAMPTZ))
RETURNING id;
