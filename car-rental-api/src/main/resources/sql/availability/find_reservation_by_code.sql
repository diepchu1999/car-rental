-- Khôi phục khóa lịch theo mã duy nhất, không theo booking_code (BR-427).
-- BR-103: vẫn đọc HELD đã hết hạn; không lọc theo đồng hồ hoặc trạng thái.
-- Đọc các cận của tstzrange, không buộc Java phân tích chuỗi range.
SELECT code,
       vehicle_id,
       lower(period) AS starts_at,
       upper(period) AS ends_at,
       upper_inf(period) AS end_unbounded,
       kind,
       status,
       booking_code,
       reason,
       hold_expires_at,
       created_at,
       status_changed_at
FROM availability.reservation
WHERE code = :code;
