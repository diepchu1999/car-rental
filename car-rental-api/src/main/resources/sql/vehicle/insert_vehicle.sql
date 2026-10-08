-- Lưu xe với dữ liệu đã được application và domain chuẩn bị.
-- Loại sở hữu: BR-001; chi nhánh: BR-003; giấy tờ: BR-005.
-- Trạng thái: BR-010 và status-flow mục 4; nhiên liệu: BR-410.
-- Chỉ bỏ qua xung đột mã nghiệp vụ để application có thể sinh mã khác.
-- Không cập nhật bản ghi cũ và không bỏ qua xung đột biển số.
INSERT INTO vehicle.vehicle (
    code,
    plate_number,
    ownership_type,
    fuel_type,
    branch_id,
    status,
    inspection_expires_on,
    liability_insurance_expires_on,
    seats,
    transmission,
    make,
    model
)
VALUES (
           :code,
           :plateNumber,
           :ownershipType,
           :fuelType,
           :branchId,
           :status,
           :inspectionExpiresOn,
           :liabilityInsuranceExpiresOn,
           :seats,
           :transmission,
           :make,
           :model
       )
ON CONFLICT (code) DO NOTHING;
