-- Số đã qua adapter mã hoá thật; không có cột nhận số rõ hoặc URL ký.
INSERT INTO identity.document_submission
    (code, account_id, document_type, status, number_key_version, number_nonce, number_ciphertext,
     number_hash, full_name, birth_date, expires_on, license_class, automatic_only, submitted_at,
     reviewed_at, reviewer_account_id, rejection_code, rejection_note)
VALUES (:code, :accountId, :type, :status, :keyVersion, :nonce, :ciphertext, :hash, :fullName,
        CAST(:birthDate AS DATE), CAST(:expiresOn AS DATE), :licenseClass, :automaticOnly,
        CAST(:submittedAt AS TIMESTAMPTZ), CAST(:reviewedAt AS TIMESTAMPTZ), :reviewerId, :rejectionCode, :rejectionNote)
RETURNING id;
