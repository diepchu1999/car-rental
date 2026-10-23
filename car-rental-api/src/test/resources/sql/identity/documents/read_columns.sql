-- Xác nhận schema không có số giấy tờ dạng rõ, URL ký hoặc kiểu nhị phân để chứa ảnh.
SELECT table_name, column_name, data_type FROM information_schema.columns
WHERE table_schema = 'identity'
  AND table_name IN ('document_upload', 'document_submission', 'document_image', 'document_head')
ORDER BY table_name, ordinal_position;
