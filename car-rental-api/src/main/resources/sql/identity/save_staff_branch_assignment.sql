-- BR-803/809: mỗi tài khoản giữ một bản gán. Không tạo tài khoản nếu mã không tồn tại.
-- Chỉ xử lý xung đột account_id; BR-807 vẫn để uq_staff_branch_manager từ chối quản lý thứ hai.
INSERT INTO identity.staff_branch_assignment (account_id, branch_id, staff_role, assigned_at)
SELECT id, :branchId, :staffRole, :assignedAt
FROM identity.account
WHERE code = :accountCode
ON CONFLICT ON CONSTRAINT uq_staff_branch_account
DO UPDATE SET branch_id = EXCLUDED.branch_id,
              staff_role = EXCLUDED.staff_role,
              assigned_at = EXCLUDED.assigned_at;
