-- UNIQUE upload_id phải chặn sử dụng lại, FK ghép phải chặn sai tài khoản/loại/mặt.
INSERT INTO identity.document_image
    (submission_id, upload_id, account_id, document_type, side, sealed_object_key, content_type, size_bytes, width, height)
VALUES (:submissionId, :uploadId, :accountId, :type, :side, :objectKey, :contentType, :sizeBytes, :width, :height)
RETURNING id;
