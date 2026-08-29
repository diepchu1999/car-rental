-- Chỉ dùng trong test để kiểm chứng truy vấn vị trí phục vụ BR-003.
-- Hai đối số là geography nên bán kính được tính bằng mét.
-- Giữ nguyên cột location để truy vấn có thể sử dụng index GiST.
SELECT b.code
FROM branch.branch AS b
WHERE public.ST_DWithin(
              b.location,
              CAST(
                      public.ST_SetSRID(
                              public.ST_MakePoint(:longitude, :latitude),
                              4326
                      ) AS public.geography
              ),
              :radius_meters
      )
ORDER BY b.code;