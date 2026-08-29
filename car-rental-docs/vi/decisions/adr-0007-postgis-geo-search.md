# ADR-0007: PostGIS cho tìm kiếm theo vị trí

- Trạng thái: ACCEPTED · 27/08/2026

## Bối cảnh
BR-125: vị trí và bán kính là **đầu vào bắt buộc** của tìm kiếm, cùng với khoảng thời gian thuê.
Truy vấn trung tâm kết hợp lọc không gian, lọc thời gian và sắp xếp theo khoảng cách.

## Quyết định
Bật PostGIS. Lưu vị trí bằng `geography(Point, 4326)`, đánh GIST index, dùng `ST_DWithin` để lọc
bán kính và `ST_Distance` để sắp xếp.

Vị trí của xe công ty là vị trí chi nhánh (BR-003); xe đối tác là nơi chủ xe hẹn (BR-004).

## Lý do
Tự viết công thức khoảng cách trong SQL, hoặc lọc theo hộp toạ độ, đều **không dùng được index
không gian** nên phải quét toàn bảng, và sai ở vùng biên. PostGIS giải đúng bài toán này, đã được
kiểm chứng rộng, và chỉ tốn một extension trên CSDL vốn đã dùng.

## Hệ quả
- Image PostgreSQL trong docker-compose phải kèm PostGIS.
- Migration đầu tiên bật extension.
- Nếu lượng truy vấn vượt khả năng PostgreSQL, bước tiếp theo là tách read model sang công cụ tìm
  kiếm chuyên dụng — **chỉ khi có số đo chứng minh**, không làm trước.
