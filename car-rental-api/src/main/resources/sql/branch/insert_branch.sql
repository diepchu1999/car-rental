-- Lưu chi nhánh với vị trí phục vụ BR-003.
-- PostGIS nhận kinh độ trước, vĩ độ sau.
-- Chỉ bỏ qua xung đột mã nghiệp vụ; không cập nhật bản ghi cũ.
INSERT INTO branch.branch (
    code,
    name,
    address,
    location
)
VALUES (
           :code,
           :name,
           :address,
           CAST(
                   public.ST_SetSRID(
                           public.ST_MakePoint(:longitude, :latitude),
                           4326
                   )
               AS public.geography
           )
       )
ON CONFLICT (code) DO NOTHING;
