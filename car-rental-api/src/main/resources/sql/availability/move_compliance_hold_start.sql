-- BR-015: chỉ thu hẹp khóa giấy tờ trên chính bản ghi cũ, không đổi trạng thái hay mốc đổi trạng thái.
-- Mốc vừa đọc nằm trong WHERE để không ghi đè kết quả gia hạn đồng thời.
UPDATE availability.reservation
SET period = tstzrange(CAST(:newStartInclusive AS timestamptz), NULL, '[)')
WHERE code = :code
  AND kind = 'COMPLIANCE_HOLD'
  AND status = 'BLOCKED'
  AND lower(period) = CAST(:expectedStartInclusive AS timestamptz);
