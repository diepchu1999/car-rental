-- BR-130: dù GT cũ đã bị thay, vẫn khoá đúng head của chủ/loại trước khi ra quyết định.
SELECT h.id
FROM identity.document_head h
JOIN identity.document_submission d
  ON d.account_id = h.account_id AND d.document_type = h.document_type
WHERE d.code = :code
FOR UPDATE OF h;
