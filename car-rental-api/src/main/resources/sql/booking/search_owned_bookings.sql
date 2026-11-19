-- security-guideline §2: chủ lấy từ CallerDirectory, không lấy từ cursor hoặc tham số client.
-- api-guideline §8: keyset giảm dần, khóa phụ là mã nghiệp vụ; trả tối đa limit + 1.
SELECT code, vehicle_code, branch_code, start_at, end_at, status,
       total_amount, deposit, balance, hold_expires_at, created_at
FROM booking.booking
WHERE customer_account_code = :customerAccountCode
  AND (CAST(:afterCreatedAt AS timestamptz) IS NULL
       OR (created_at, code COLLATE "C") <
          (CAST(:afterCreatedAt AS timestamptz), CAST(:afterCode AS text) COLLATE "C"))
ORDER BY created_at DESC, code COLLATE "C" DESC
LIMIT :fetchLimit;
