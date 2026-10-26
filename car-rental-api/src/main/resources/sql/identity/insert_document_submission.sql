-- BR-130: chỉ nhận phong bì số đã bảo vệ, không có số rõ hoặc trạng thái từ client.
INSERT INTO identity.document_submission (
    code, account_id, document_type, status, number_key_version, number_nonce, number_ciphertext,
    number_hash, full_name, birth_date, expires_on, license_class, automatic_only, submitted_at
) VALUES (
    :code, (SELECT id FROM identity.account WHERE code = :accountCode), :documentType, 'PENDING',
    :keyVersion, :nonce, :ciphertext, :hash, :fullName, :birthDate, :expiresOn,
    :licenseClass, :automaticOnly, :submittedAt
)
ON CONFLICT ON CONSTRAINT uq_document_submission_code DO NOTHING;
