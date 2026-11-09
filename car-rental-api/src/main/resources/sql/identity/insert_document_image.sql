-- FK ghép kiểm chủ/loại/mặt; UNIQUE upload_id là cửa cuối chống tái sử dụng AN.
INSERT INTO identity.document_image (
    submission_id, upload_id, account_id, document_type, side,
    sealed_object_key, content_type, size_bytes, width, height
) VALUES (
    (SELECT id FROM identity.document_submission WHERE code = :code),
    (SELECT id FROM identity.document_upload WHERE code = :uploadCode),
    (SELECT id FROM identity.account WHERE code = :accountCode), :documentType, :side,
    :sealedKey, :contentType, :sizeBytes, :width, :height
);
