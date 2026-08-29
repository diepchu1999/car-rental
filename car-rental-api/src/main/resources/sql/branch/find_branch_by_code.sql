-- Đọc chi tiết chi nhánh theo mã nghiệp vụ.
-- Tách vị trí thành vĩ độ và kinh độ để ánh xạ sang BranchDetail.
SELECT
    b.id,
    b.code,
    public.ST_Y(CAST(b.location AS public.geometry)) AS latitude,
    public.ST_X(CAST(b.location AS public.geometry)) AS longitude
FROM branch.branch AS b
WHERE b.code = :code;