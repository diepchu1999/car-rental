-- BR-803, ADR-0008: ghép nội bộ identity; mã chi nhánh phải tra qua branch.api ở application.
SELECT a.code AS account_code, s.branch_id, s.staff_role, s.assigned_at
FROM identity.staff_branch_assignment s
JOIN identity.account a ON a.id = s.account_id
WHERE a.code = :accountCode;
