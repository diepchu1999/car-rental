-- Đọc dữ liệu để ánh xạ sang VehicleDetail.
-- Không kiểm lại điều kiện duyệt khi đọc hồ sơ xe.
-- Chỉ đọc schema vehicle; thông tin chi nhánh lấy qua API của module branch.
SELECT
    v.id,
    v.code,
    v.plate_number,
    v.ownership_type,
    v.fuel_type,
    v.branch_id,
    v.status,
    v.inspection_expires_on,
    v.liability_insurance_expires_on,
    v.seats,
    v.transmission,
    v.make,
    v.model
FROM vehicle.vehicle AS v
WHERE v.code = :code;
