-- BR-104: mọi nguyên nhân khiến xe bận nằm chung bảng reservation.
-- Tập trạng thái phải khớp reservation_no_overlap; COMPLETED giữ cả đệm.
-- BR-103: HELD quá hạn chưa được nhả vẫn chặn, không lọc hold_expires_at.
-- Application đã cộng đệm theo BR-109, BR-116; không cộng thêm ở đây.
SELECT DISTINCT vehicle_id
FROM availability.reservation
WHERE vehicle_id IN (:candidateIds)
  AND status IN ('HELD', 'CONFIRMED', 'IN_USE', 'BLOCKED', 'COMPLETED')
  AND period && tstzrange(
      CAST(:startInclusive AS timestamptz),
      CAST(:endExclusive AS timestamptz),
      '[)'
  );
