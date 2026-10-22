-- Đọc độc lập sau commit để xác nhận đúng người thắng và không còn bản ghi của bên thua.
SELECT account_id, branch_id, staff_role
FROM identity.staff_branch_assignment
WHERE branch_id IN (:branchIds)
ORDER BY account_id;
