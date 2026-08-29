-- Chỉ dùng trong test để kiểm tra ràng buộc CSDL.
-- Không dùng ON CONFLICT vì cần PostgreSQL báo lỗi trùng mã.
INSERT INTO branch.branch (
    code,
    location
)
VALUES (
           :code,
           CAST(:location AS public.geography)
       );