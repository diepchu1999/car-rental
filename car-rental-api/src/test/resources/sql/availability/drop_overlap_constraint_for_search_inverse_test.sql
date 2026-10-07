-- Chỉ dùng cho phép thử đảo trong Testcontainers của SearchHoldConcurrencyIntegrationTest.
-- KHÔNG chạy trên database local. Không sửa migration hoặc SQL production.
-- Khi bỏ cơ chế bảo vệ, hai hold cùng commit và assertion đúng một người thắng phải đỏ.
-- @DirtiesContext đóng container sau lớp test; lần chạy sau dựng schema từ migration gốc.
ALTER TABLE availability.reservation DROP CONSTRAINT reservation_no_overlap;
