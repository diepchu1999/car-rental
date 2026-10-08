-- Tra tham chiếu nội bộ theo ADR-0008; không JOIN sang schema vehicle.
SELECT
    b.id,
    b.code,
    b.name,
    b.address,
    public.ST_Y(CAST(b.location AS public.geometry)) AS latitude,
    public.ST_X(CAST(b.location AS public.geometry)) AS longitude
FROM branch.branch AS b
WHERE b.id = :id;
