-- BR-003, BR-808, ADR-0007: chỉ đọc dữ liệu thuộc branch.
-- Geography dùng mét; giữ cột location nguyên trạng để ST_DWithin dùng được index GiST.
-- ST_MakePoint nhận kinh độ trước, vĩ độ sau.
WITH origin AS (
    SELECT CAST(public.ST_SetSRID(public.ST_MakePoint(:longitude, :latitude), 4326)
                AS public.geography) AS point
)
SELECT b.id, b.code, b.name, b.address,
       public.ST_Distance(b.location, origin.point) AS distance_meters
FROM branch.branch AS b
CROSS JOIN origin
WHERE public.ST_DWithin(b.location, origin.point, :radius_meters)
ORDER BY distance_meters, b.id;
