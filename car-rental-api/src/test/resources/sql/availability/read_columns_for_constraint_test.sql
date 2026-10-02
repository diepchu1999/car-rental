-- Kiểm khóa identity và hai mốc thời gian do ứng dụng bắt buộc cung cấp.
SELECT column_name, column_default, is_nullable, identity_generation
FROM information_schema.columns
WHERE table_schema = 'availability' AND table_name = 'reservation'
  AND column_name IN ('id', 'created_at', 'status_changed_at');
