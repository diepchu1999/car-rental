-- Test mã hoá cần đối chiếu phong bì lưu; không in ResultSet vào log.
SELECT id, code, account_id, document_type, status, number_key_version, number_nonce,
       number_ciphertext, number_hash, rejection_code, rejection_note
FROM identity.document_submission WHERE id = :id;
