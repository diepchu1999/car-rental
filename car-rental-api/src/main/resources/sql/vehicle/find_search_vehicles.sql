-- BR-010/126: chỉ xe đã duyệt và đúng thuộc tính; không lọc hoặc xếp hạng theo sở hữu (BR-112).
-- Lịch trống thuộc availability; thế chấp thuộc CollateralPolicyResolver (BR-209).
SELECT id, code, branch_id, ownership_type, seats, transmission, fuel_type, make, model
FROM vehicle.vehicle
WHERE status = 'ACTIVE'
  AND branch_id IN (:branch_ids)
  AND (:seats IS NULL OR seats = :seats)
  AND (:transmission IS NULL OR transmission = :transmission)
  AND (:fuel_type IS NULL OR fuel_type = :fuel_type)
  AND (:make IS NULL OR lower(regexp_replace(make, '^[[:space:]]+|[[:space:]]+$', '', 'g')) = lower(:make))
  AND (:model IS NULL OR lower(regexp_replace(model, '^[[:space:]]+|[[:space:]]+$', '', 'g')) = lower(:model))
ORDER BY id;
