-- BR-130: chỉ use case xác minh đã kiểm quyền được dùng; không đọc xuyên schema hay chọn lại head.
-- LEFT JOIN để adapter phát hiện bản nộp thiếu ảnh thay vì âm thầm trả không tồn tại.
SELECT s.code, a.code AS account_code, s.document_type, s.status,
       s.expires_on, s.issued_on, s.submitted_at, s.reviewed_at, s.rejection_code, s.rejection_note,
       s.number_key_version, s.number_nonce, s.number_ciphertext, s.number_hash,
       s.full_name, s.birth_date, s.license_class, s.automatic_only,
       fu.code AS front_upload_code, f.sealed_object_key AS front_object_key,
       f.content_type AS front_content_type, f.size_bytes AS front_size_bytes,
       f.width AS front_width, f.height AS front_height,
       bu.code AS back_upload_code, b.sealed_object_key AS back_object_key,
       b.content_type AS back_content_type, b.size_bytes AS back_size_bytes,
       b.width AS back_width, b.height AS back_height
FROM identity.document_submission s
JOIN identity.account a ON a.id = s.account_id
LEFT JOIN identity.document_image f ON f.submission_id = s.id AND f.side = 'FRONT'
LEFT JOIN identity.document_upload fu ON fu.id = f.upload_id
LEFT JOIN identity.document_image b ON b.submission_id = s.id AND b.side = 'BACK'
LEFT JOIN identity.document_upload bu ON bu.id = b.upload_id
WHERE s.code = :code;
