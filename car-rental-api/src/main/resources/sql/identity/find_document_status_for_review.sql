-- BR-130: chỉ use case đã kiểm quyền được reload quyết định; không đọc số/nhân thân/người duyệt/ảnh.
SELECT s.code, s.document_type, s.status, s.expires_on, s.issued_on, s.submitted_at,
       s.reviewed_at, s.rejection_code, s.rejection_note
FROM identity.document_submission s
WHERE s.code = :code;
