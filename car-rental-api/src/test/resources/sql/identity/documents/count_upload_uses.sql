-- Không đọc nội dung ảnh; chỉ chứng minh một lượt tải có đúng một liên kết sau cuộc đua.
SELECT COUNT(*) FROM identity.document_image WHERE upload_id = :uploadId;
