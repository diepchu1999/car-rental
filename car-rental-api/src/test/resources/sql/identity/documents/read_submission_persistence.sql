-- Đọc độc lập để kiểm adapter; không in map chứa phong bì/PII vào assertion hoặc log.
SELECT s.id, s.code, s.status, s.number_key_version, s.number_nonce, s.number_ciphertext,
       s.number_hash, s.full_name, s.birth_date, s.expires_on, s.license_class, s.automatic_only,
       s.submitted_at,
       (SELECT count(*) FROM identity.document_image i WHERE i.submission_id = s.id) AS image_count,
       latest.code AS latest_code, active.code AS active_code
FROM identity.document_submission s
JOIN identity.account a ON a.id = s.account_id
LEFT JOIN identity.document_head h ON h.account_id = s.account_id AND h.document_type = s.document_type
LEFT JOIN identity.document_submission latest ON latest.id = h.latest_submission_id
LEFT JOIN identity.document_submission active ON active.id = h.active_verified_submission_id
WHERE s.code = :code AND a.code = :accountCode;
