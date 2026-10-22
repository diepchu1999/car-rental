-- Chỉ dùng trong test: kiểm định danh dòng không đổi và không để lại bản gán cũ sau cập nhật.
SELECT id, account_id, branch_id, staff_role, assigned_at
FROM identity.staff_branch_assignment
WHERE branch_id IN (:branchIds)
ORDER BY account_id;
