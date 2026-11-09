-- Transaction đối chứng giữ khoá độc lập với câu SQL production đang được kiểm.
SELECT h.id
FROM identity.document_head h
JOIN identity.account a ON a.id = h.account_id
WHERE a.code = :accountCode AND h.document_type = :documentType
FOR UPDATE OF h;
