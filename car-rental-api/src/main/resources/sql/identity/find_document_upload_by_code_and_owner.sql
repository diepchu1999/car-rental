-- BR-130, security-guideline §2: không đọc lượt tải của khách khác chỉ bằng mã AN.
SELECT u.code, a.code AS account_code, u.document_type, u.side, u.incoming_object_key,
       u.created_at, u.expires_at
FROM identity.document_upload u
JOIN identity.account a ON a.id = u.account_id
WHERE u.code = :code AND a.code = :accountCode;
