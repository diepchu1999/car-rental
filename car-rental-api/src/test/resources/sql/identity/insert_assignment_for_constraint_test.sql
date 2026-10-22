-- Vai và chi nhánh là dữ liệu thử; không thay cho kiểm quyền trong use case.
INSERT INTO identity.staff_branch_assignment (account_id, branch_id, staff_role, assigned_at)
VALUES (:accountId, :branchId, :role, CAST(:assignedAt AS TIMESTAMPTZ))
RETURNING id;
