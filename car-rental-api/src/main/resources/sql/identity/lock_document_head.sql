-- BR-130: nộp và xác minh phải cùng khoá hàng head này trước khi đổi trạng thái/con trỏ.
SELECT h.id
FROM identity.document_head h
JOIN identity.account a ON a.id = h.account_id
WHERE a.code = :accountCode AND h.document_type = :documentType
FOR UPDATE OF h;
