-- BR-130: hai loại trong một snapshot, chỉ bản VERIFIED đang được head chọn, đúng chủ.
-- Không chọn latest, MAX hoặc lọc hạn; cổng điều kiện thuê phải báo hết hạn, không fallback lịch sử.
-- Không đọc số/phong bì/hash, tên/ngày sinh, reviewer/ghi chú hoặc ảnh.
SELECT s.code, s.document_type, s.expires_on, s.license_class, s.automatic_only
FROM identity.document_head h
JOIN identity.account a ON a.id = h.account_id
JOIN identity.document_submission s ON s.id = h.active_verified_submission_id
WHERE a.code = :accountCode AND s.status = 'VERIFIED'
ORDER BY h.document_type;
