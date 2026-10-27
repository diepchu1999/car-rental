-- BR-130, security-guideline §2/§3: không đọc số, tên, người duyệt hay ảnh; luôn kiểm đúng chủ.
SELECT s.code, s.document_type, s.status, s.expires_on, s.submitted_at,
       s.reviewed_at, s.rejection_code, s.rejection_note
FROM identity.document_submission s
JOIN identity.account a ON a.id = s.account_id
WHERE s.code = :code AND a.code = :accountCode;
